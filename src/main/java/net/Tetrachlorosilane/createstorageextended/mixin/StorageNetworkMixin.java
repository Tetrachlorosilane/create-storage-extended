package net.Tetrachlorosilane.createstorageextended.mixin;

import net.Tetrachlorosilane.createstorageextended.network.StorageNetworkManager;
import net.fxnt.fxntstorage.storage_network.StorageNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;
import java.util.UUID;

@Mixin(value = StorageNetwork.class, remap = false)
public abstract class StorageNetworkMixin {

    /**
     * Replace BFS-based component discovery with persisted data lookup.
     * Fetches the controller's networkId from its BE on every call, so splits
     * and merges are reflected immediately without stale cached values.
     */
    @Inject(method = "getConnectedComponents", at = @At("HEAD"), cancellable = true, remap = false)
    private void onGetConnectedComponents(@Nullable Level level, BlockPos origin,
                                          CallbackInfoReturnable<Set<BlockPos>> cir) {
        if (!(level instanceof ServerLevel serverLevel)) return;

        var be = serverLevel.getBlockEntity(origin);
        if (be == null) return;

        UUID networkId = null;
        try {
            var method = be.getClass().getMethod("getStorageNetworkId");
            networkId = (UUID) method.invoke(be);
        } catch (Exception ignored) {
        }

        if (networkId == null) return; // fall through to original BFS

        Set<BlockPos> members = StorageNetworkManager.getInstance()
                .getNetworkMembers(serverLevel, networkId);
        if (!members.isEmpty()) {
            cir.setReturnValue(members);
        }
    }
}
