package net.Tetrachlorosilane.createstorageextended.mixin;

import net.Tetrachlorosilane.createstorageextended.network.INetworkComponent;
import net.Tetrachlorosilane.createstorageextended.network.StorageNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/**
 * Hooks into {@code Level.setBlock} to handle both removal and placement
 * of storage network blocks.
 * <p>
 * The persisted topology is only mutated <em>after</em> {@code setBlock} has
 * actually changed the world state:
 * <ul>
 *   <li>{@code HEAD} - captures the old block state and the old network id
 *       (if any) without touching the topology.</li>
 *   <li>{@code RETURN} - pops the captured state and commits the topology
 *       change only when {@code setBlock} returned {@code true}. Failed or
 *       cancelled calls (any early return) therefore never modify the
 *       topology, so the persisted data cannot diverge from the world.</li>
 * </ul>
 * Because {@code setBlock} can be re-entered (block updates that place or
 * remove other blocks while the outer call is still running), captures are
 * kept on a per-thread stack so inner and outer calls pair up correctly.
 * <p>
 * On placement, the block entity is only queried after the successful return,
 * at which point it is actually installed in the world - this guarantees the
 * generated network UUID reaches the newly placed block entity (previously it
 * was read at {@code HEAD}, before the entity existed, so the UUID was lost).
 */
@Mixin(Level.class)
public abstract class LevelSetBlockMixin {

    @Unique
    private static final TagKey<Block> STORAGE_NETWORK_BLOCK_TAG =
            BlockTags.create(ResourceLocation.fromNamespaceAndPath("fxntstorage", "storage_network_block"));

    @Unique
    private final ThreadLocal<Deque<CapturedState>> capturedStates = ThreadLocal.withInitial(ArrayDeque::new);

    private record CapturedState(boolean oldIsNet, boolean newIsNet, @Nullable UUID oldNetworkId) {}

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
            at = @At("HEAD"))
    private void captureOldState(BlockPos pos, BlockState newState, int flags, CallbackInfoReturnable<Boolean> cir) {
        Level self = (Level) (Object) this;
        if (self.isClientSide() || !(self instanceof ServerLevel serverLevel)) return;

        BlockState oldState = self.getBlockState(pos);
        boolean oldIsNet = oldState.is(STORAGE_NETWORK_BLOCK_TAG);
        boolean newIsNet = newState.is(STORAGE_NETWORK_BLOCK_TAG);
        if (oldIsNet == newIsNet) return; // no topology change

        UUID oldNetworkId = null;
        if (oldIsNet) {
            // Capture the old network id while the old block entity still exists.
            BlockEntity oldBe = self.getBlockEntity(pos);
            if (oldBe instanceof INetworkComponent component) {
                oldNetworkId = component.getStorageNetworkId();
            }
            if (oldNetworkId == null) {
                oldNetworkId = StorageNetworkManager.getInstance().getNetworkId(serverLevel, pos);
            }
        }

        capturedStates.get().push(new CapturedState(oldIsNet, newIsNet, oldNetworkId));
    }

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
            at = @At("RETURN"))
    private void commitTopology(BlockPos pos, BlockState newState, int flags, CallbackInfoReturnable<Boolean> cir) {
        Level self = (Level) (Object) this;
        if (self.isClientSide() || !(self instanceof ServerLevel serverLevel)) return;

        Deque<CapturedState> stack = capturedStates.get();
        CapturedState captured = stack.poll();
        if (captured == null) return;

        // setBlock failed, was cancelled, or did not change the world -
        // the topology must not be updated for a change that never happened.
        if (!Boolean.TRUE.equals(cir.getReturnValue())) return;

        if (captured.oldIsNet()) {
            // Network block removed successfully.
            if (captured.oldNetworkId() != null) {
                StorageNetworkManager.getInstance().onBlockRemoved(serverLevel, pos, captured.oldNetworkId());
            }
        } else {
            // Network block placed successfully. The block entity is now
            // installed, so we can assign the generated network UUID to it.
            BlockEntity be = self.getBlockEntity(pos);
            UUID existingId = null;
            if (be instanceof INetworkComponent component) {
                existingId = component.getStorageNetworkId();
            }
            UUID newId = StorageNetworkManager.getInstance().onBlockPlaced(serverLevel, pos, existingId);
            if (newId != null && be instanceof INetworkComponent component) {
                component.setStorageNetworkId(newId);
                be.setChanged();
            }
        }
    }
}
