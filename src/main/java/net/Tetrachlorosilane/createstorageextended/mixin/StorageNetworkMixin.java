package net.Tetrachlorosilane.createstorageextended.mixin;

import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollOptionBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import net.Tetrachlorosilane.createstorageextended.network.INetworkComponent;
import net.Tetrachlorosilane.createstorageextended.network.StorageNetworkManager;
import net.fxnt.fxntstorage.controller.StorageControllerEntity;
import net.fxnt.fxntstorage.simple_storage.SimpleStorageBoxEntity;
import net.fxnt.fxntstorage.storage_network.StorageNetwork;
import net.fxnt.fxntstorage.storage_network.StorageNetwork.StorageNetworkItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Replaces the upstream component discovery ({@code getConnectedComponents})
 * with the persisted topology lookup, and adds an item -> box index to
 * {@code StorageNetwork} so {@code findBestTargetBox} - called on every
 * network insert, {@code canPlaceItem} and {@code isItemValid} - no longer
 * has to scan all boxes of the network.
 * <p>
 * The index is rebuilt together with the network table (at the end of
 * {@code getBoxes}) and is therefore always a projection of the current
 * {@code boxes} list. Lookup re-verifies candidates in real time, so a filter
 * changed between refreshes can at worst cost a few extra candidate checks -
 * never a wrong match.
 * <p>
 * Performance:
 * <ul>
 *   <li>the returned component set is restricted to <em>loaded</em> members -
 *       the upstream {@code getBoxes}/{@code checkBoxes} call
 *       {@code Level.getBlockEntity} on every member, which would force-load
 *       chunks of members that are outside the loaded area;</li>
 *   <li>every {@code Level.getBlockEntity} in the upstream network code is
 *       redirected through a load-safe gate so unloaded members are treated as
 *       absent instead of being force-loaded;</li>
 *   <li>{@code checkBoxes} - the per-tick O(boxes) validation scan - is
 *       skipped entirely while the topology revision is unchanged.</li>
 * </ul>
 */
@Mixin(value = StorageNetwork.class, remap = false)
public abstract class StorageNetworkMixin {

    @Shadow
    @Final
    private StorageControllerEntity controller;

    @Shadow
    @Final
    private NonNullList<StorageNetworkItem> boxes;

    @Shadow
    @Nullable
    private Level level;

    /** ItemKey -> ordinary boxes (in boxes order) whose filter matches that key. */
    @Unique
    private Map<String, List<SimpleStorageBoxEntity>> createstorageextended$itemIndex;

    /** Boxes with an empty filter, in boxes order (they accept any item). */
    @Unique
    private List<SimpleStorageBoxEntity> createstorageextended$emptyBoxes;

    /** Boxes with a compacting upgrade (dynamic filter - not item-indexed). */
    @Unique
    private List<SimpleStorageBoxEntity> createstorageextended$compactBoxes;

    /**
     * Topology revision seen by the last {@code checkBoxes} run. While it stays
     * unchanged the cached box list is valid and the per-tick scan can be
     * skipped.
     */
    @Unique
    private int createstorageextended$lastRevision = -1;

    @Shadow
    private static String itemKey(ItemStack stack) {
        throw new AssertionError();
    }

    @Shadow
    private boolean acceptsCompactingItem(SimpleStorageBoxEntity box, ItemStack itemStack) {
        throw new AssertionError();
    }

    /**
     * Replaces BFS-based component discovery with a lookup in the persisted
     * topology. The controller's networkId is fetched from its BE on every
     * call; the topology itself is kept up to date by the deferred tick pass
     * in {@link StorageNetworkManager}, which resolves all changes since the
     * previous tick in one order-independent pass.
     * <p>
     * Only loaded positions are returned: the upstream network object reads
     * {@code Level.getBlockEntity} for every component and every box, and on a
     * server level that would force-load chunks of unloaded members. The
     * controller origin is always loaded, so the result is never empty.
     */
    @Inject(method = "getConnectedComponents", at = @At("HEAD"), cancellable = true, remap = false)
    private void onGetConnectedComponents(@Nullable Level level, BlockPos origin,
                                          CallbackInfoReturnable<Set<BlockPos>> cir) {
        if (!(level instanceof ServerLevel serverLevel)) return;

        var be = serverLevel.getBlockEntity(origin);
        if (!(be instanceof INetworkComponent component)) return;

        UUID networkId = component.getStorageNetworkId();
        if (networkId == null) return; // fall through to original BFS

        Set<BlockPos> members = StorageNetworkManager.getInstance()
                .getNetworkMembers(serverLevel, networkId);
        if (members.isEmpty()) return;

        Set<BlockPos> loaded = null;
        for (BlockPos pos : members) {
            if (serverLevel.isLoaded(pos)) {
                if (loaded == null) loaded = new HashSet<>();
                loaded.add(pos);
            }
        }
        if (loaded != null) {
            cir.setReturnValue(loaded);
        }
    }

    /**
     * Skips the per-tick {@code checkBoxes} scan while the topology revision
     * is unchanged: the box list is then still a valid projection of the
     * current world state, so validating every box every tick is pure waste.
     * On a revision change the original scan runs (load-safe via the
     * {@code getBlockEntity} redirect) and triggers the refresh itself.
     */
    @Inject(method = "checkBoxes", at = @At("HEAD"), cancellable = true, remap = false)
    private void onCheckBoxes(CallbackInfo ci) {
        if (level == null) return;
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (!(controller instanceof INetworkComponent ctrl)) return;
        UUID networkId = ctrl.getStorageNetworkId();
        if (networkId == null) return;

        int revision = StorageNetworkManager.getInstance().getTopologyRevision(serverLevel);
        if (revision == createstorageextended$lastRevision) {
            ci.cancel();
        } else {
            createstorageextended$lastRevision = revision;
        }
    }

    // ========== Load-safe getBlockEntity (never force-load chunks) ==========

    @Redirect(method = "checkBoxes",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;"))
    private BlockEntity createstorageextended$loadSafeGetBlockEntityCheckBoxes(Level level, BlockPos pos) {
        return level.isLoaded(pos) ? level.getBlockEntity(pos) : null;
    }

    @Redirect(method = "refreshStorageNetwork",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;"))
    private BlockEntity createstorageextended$loadSafeGetBlockEntityRefresh(Level level, BlockPos pos) {
        return level.isLoaded(pos) ? level.getBlockEntity(pos) : null;
    }

    @Redirect(method = "getBoxes(Lnet/minecraft/world/level/Level;Ljava/util/Set;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;"))
    private BlockEntity createstorageextended$loadSafeGetBlockEntityGetBoxes(Level level, BlockPos pos) {
        return level.isLoaded(pos) ? level.getBlockEntity(pos) : null;
    }

    /** Builds the item index together with the network table. */
    @Inject(method = "getBoxes(Lnet/minecraft/world/level/Level;Ljava/util/Set;)V", at = @At("TAIL"), remap = false)
    private void createstorageextended$buildItemIndex(Level level, Set<BlockPos> components, CallbackInfo ci) {
        Map<String, List<SimpleStorageBoxEntity>> index = new HashMap<>();
        List<SimpleStorageBoxEntity> emptyBoxes = new ArrayList<>();
        List<SimpleStorageBoxEntity> compactBoxes = new ArrayList<>();

        for (StorageNetworkItem networkItem : boxes) {
            SimpleStorageBoxEntity box = networkItem.simpleStorageBoxEntity;
            if (box.compactingUpgrade && box.compactingChain != null) {
                compactBoxes.add(box);
            } else if (box.getFilterItem().isEmpty()) {
                emptyBoxes.add(box);
            } else {
                index.computeIfAbsent(itemKey(box.getFilterItem()), k -> new ArrayList<>()).add(box);
            }
        }

        createstorageextended$itemIndex = index;
        createstorageextended$emptyBoxes = emptyBoxes;
        createstorageextended$compactBoxes = compactBoxes;
    }

    /**
     * Fast path for {@code findBestTargetBox}: candidate boxes come from the
     * item index instead of a full network scan. Candidates are re-verified in
     * real time so a stale index can never produce a wrong match. Semantics
     * match the original method: first matched box with space wins, empty
     * boxes are the fallback when the controller allows them, void boxes last.
     */
    @Inject(method = "findBestTargetBox", at = @At("HEAD"), cancellable = true, remap = false)
    private void createstorageextended$findBestTargetBoxFast(ItemStack itemStack,
                                                             CallbackInfoReturnable<SimpleStorageBoxEntity> cir) {
        if (createstorageextended$itemIndex == null) return; // index not built yet -> original scan

        SimpleStorageBoxEntity emptyBox = null;
        SimpleStorageBoxEntity voidBox = null;

        // 1. Item-matched ordinary boxes (index order == boxes order).
        List<SimpleStorageBoxEntity> matched = createstorageextended$itemIndex.get(itemKey(itemStack));
        if (matched != null) {
            for (SimpleStorageBoxEntity box : matched) {
                if (!ItemStack.isSameItemSameComponents(box.getFilterItem(), itemStack)) continue;
                boolean hasRealSpace = box.getMaxItemCapacity() - box.getStoredAmount() > 0;
                if (hasRealSpace) {
                    cir.setReturnValue(box);
                    return;
                }
                if (box.hasVoidUpgrade() && voidBox == null) voidBox = box;
            }
        }

        // 2. Compacting boxes (usually few; their filter is dynamic).
        if (createstorageextended$compactBoxes != null) {
            for (SimpleStorageBoxEntity box : createstorageextended$compactBoxes) {
                if (!acceptsCompactingItem(box, itemStack)) continue;
                boolean hasRealSpace = box.getMaxItemCapacity() - box.getStoredAmount() > 0;
                if (hasRealSpace) {
                    cir.setReturnValue(box);
                    return;
                }
                if (box.hasVoidUpgrade() && voidBox == null) voidBox = box;
            }
        }

        // 3. Empty-filter boxes: first usable one (matches the original which
        //    only records the first empty box).
        if (createstorageextended$emptyBoxes != null) {
            for (SimpleStorageBoxEntity box : createstorageextended$emptyBoxes) {
                if (!box.getFilterItem().isEmpty()) continue;
                if (box.getMaxItemCapacity() - box.getStoredAmount() > 0) {
                    emptyBox = box;
                    break;
                }
            }
        }

        // 4. Same fallback decision as the original method.
        ScrollValueBehaviour behaviour = controller.getBehaviour(ScrollOptionBehaviour.TYPE);
        boolean allowEmpty = behaviour == null || behaviour.getValue() == 0;
        if (allowEmpty && emptyBox != null) {
            cir.setReturnValue(emptyBox);
            return;
        }
        cir.setReturnValue(voidBox);
    }
}
