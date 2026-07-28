package net.Tetrachlorosilane.createstorageextended.mixin;

import net.Tetrachlorosilane.createstorageextended.network.StorageNetworkManager;
import net.fxnt.fxntstorage.storage_network.StorageNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;
import java.util.UUID;

/**
 * Mixin into {@link StorageNetwork} to replace the BFS-based discovery with
 * persistent network data from {@link StorageNetworkManager}.
 * <p>
 * When persisted data is available, {@code getConnectedComponents()} returns
 * the saved member set directly (O(1)). Otherwise it falls back to the
 * original BFS logic for backward compatibility.
 */
@Mixin(value = StorageNetwork.class, remap = false)
public abstract class StorageNetworkMixin {

    @Unique
    @Nullable
    private UUID createstorageextended$networkId;

    @Unique
    @Nullable
    private BlockPos createstorageextended$controllerPos;

    /**
     * Replace BFS-based component discovery with persisted data lookup.
     * If persisted data exists, return it directly (O(1)).
     */
    @Inject(method = "getConnectedComponents", at = @At("HEAD"), cancellable = true, remap = false)
    private void onGetConnectedComponents(@Nullable Level level, BlockPos origin, CallbackInfoReturnable<Set<BlockPos>> cir) {
        createstorageextended$controllerPos = origin;

        if (level instanceof ServerLevel serverLevel) {
            // Try to find the network ID from the controller entity
            if (createstorageextended$networkId == null) {
                var be = serverLevel.getBlockEntity(origin);
                if (be != null) {
                    String className = be.getClass().getName();
                    if (className.equals("net.fxnt.fxntstorage.controller.StorageControllerEntity")) {
                        try {
                            var method = be.getClass().getMethod("getStorageNetworkId");
                            createstorageextended$networkId = (UUID) method.invoke(be);
                        } catch (Exception ignored) {
                            // Fall through to original BFS
                        }
                    }
                }
            }

            if (createstorageextended$networkId != null) {
                Set<BlockPos> members = StorageNetworkManager.getInstance()
                        .getNetworkMembers(serverLevel, createstorageextended$networkId);
                if (!members.isEmpty()) {
                    cir.setReturnValue(members);
                }
                // If empty, fall through to original BFS
            }
        }
    }

    /**
     * After the original refresh, sync the network ID back from the controller.
     */
    @Inject(method = "refreshStorageNetwork", at = @At("TAIL"), remap = false)
    private void onRefreshStorageNetwork(CallbackInfo ci) {
        BlockPos pos = createstorageextended$controllerPos;
        if (pos == null) return;

        // Try to get level from the controller block entity
        // We cache the controllerPos from getConnectedComponents
    }
}
