package net.Tetrachlorosilane.createstorageextended.mixin;

import net.Tetrachlorosilane.createstorageextended.network.INetworkComponent;
import net.Tetrachlorosilane.createstorageextended.network.StorageNetworkManager;
import net.Tetrachlorosilane.createstorageextended.network.StorageNetworkSync;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Objects;
import java.util.UUID;

/**
 * Makes the interface a {@link INetworkComponent} and keeps its upstream
 * {@code controller} binding in sync with the persisted network topology.
 * <p>
 * The upstream class hands out access purely through its {@code controller}
 * reference ({@code getItemHandler()}, the filtered variant's wrapper and
 * {@code StorageInterfaceUnpacking} all read it directly), while the extended
 * mod manages network membership by {@code networkId}. After a split the
 * interface's {@code networkId} moves to the new network but the upstream
 * {@code controller} reference could still point at a controller of the old
 * network, and upstream {@code checkController()} only verifies that the
 * controller block entity still exists. This mixin therefore:
 * <ul>
 *   <li>invalidates the position's capabilities whenever the {@code networkId}
 *       changes, so external devices holding a cached {@code IItemHandler} or
 *       {@code BlockCapability} re-query;</li>
 *   <li>invalidates capabilities whenever the controller reference is dropped
 *       (upstream {@code forgetController()} does not, so cached handlers
 *       would otherwise outlive the disconnection);</li>
 *   <li>re-checks the binding every tick as a safety net for states that did
 *       not go through the topology pass (pre-fix saves, chunk-load
 *       re-registration merges).</li>
 * </ul>
 * The topology pass itself performs the same reconciliation in the same tick
 * once all network ids are final (see {@link StorageNetworkSync}).
 */
@Mixin(targets = "net.fxnt.fxntstorage.controller.StorageInterfaceEntity", remap = false)
public abstract class StorageInterfaceEntityMixin implements INetworkComponent {

    @Unique
    @Nullable
    private UUID createstorageextended$networkId;

    @Unique
    private boolean createstorageextended$registered;

    @Override
    public UUID getStorageNetworkId() {
        return createstorageextended$networkId;
    }

    @Override
    public void setStorageNetworkId(@Nullable UUID networkId) {
        UUID oldId = this.createstorageextended$networkId;
        this.createstorageextended$networkId = networkId;
        if (!Objects.equals(oldId, networkId)) {
            // The network binding changed. Any external device that cached the
            // previous item handler via a BlockCapability must re-query. The
            // controller-drop path invalidates on its own (see
            // onForgetController); this covers every other id change (merges
            // that keep the binding, corrections at chunk load, rebuilds).
            BlockEntity be = (BlockEntity) (Object) this;
            Level level = be.getLevel();
            if (level != null && !level.isClientSide()) {
                level.invalidateCapabilities(be.getBlockPos());
            }
        }
    }

    /**
     * Upstream {@code forgetController()} only nulls the reference; devices
     * that cached the old item handler would keep using it. Invalidate the
     * position's capabilities so they re-query and fall back to the empty
     * handler.
     */
    @Inject(method = "forgetController", at = @At("TAIL"), remap = false)
    private void onForgetController(CallbackInfo ci) {
        BlockEntity be = (BlockEntity) (Object) this;
        Level level = be.getLevel();
        if (level != null && !level.isClientSide()) {
            level.invalidateCapabilities(be.getBlockPos());
        }
    }

    @Inject(method = "serverTick", at = @At("HEAD"), remap = false)
    private void onServerTick(Level level, BlockPos blockPos, BlockState state, CallbackInfo ci) {
        if (!(level instanceof ServerLevel serverLevel)) return;

        if (!createstorageextended$registered && createstorageextended$networkId != null) {
            createstorageextended$registered = true;
            StorageNetworkManager.getInstance().registerComponent(serverLevel, blockPos, createstorageextended$networkId);
        }

        // Safety net: drop a controller reference that no longer belongs to
        // this interface's network. The topology pass already does this in the
        // same tick; this catches interfaces restored from pre-fix saves and
        // id changes that did not go through the pass.
        StorageNetworkSync.reconcileController(serverLevel, blockPos);
    }
}
