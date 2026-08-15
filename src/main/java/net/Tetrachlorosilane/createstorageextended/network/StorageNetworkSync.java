package net.Tetrachlorosilane.createstorageextended.network;

import net.fxnt.fxntstorage.controller.StorageInterfaceEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.UUID;

/**
 * Keeps the upstream {@link StorageInterfaceEntity#controller} Java reference
 * in sync with the persisted network topology maintained by
 * {@link StorageNetworkManager}.
 * <p>
 * The upstream class decides access purely from its {@code controller} field:
 * {@code getItemHandler()} delegates to it, the filtered variant
 * ({@code StorageInterfaceFilteredEntity}) wraps it, and
 * {@code StorageInterfaceUnpacking} reads it directly. After a network split
 * the extended mod reassigns the interface's {@code networkId} to the new
 * network, but the upstream field can still point at a controller of the old
 * network - and the upstream {@code checkController()} only verifies that the
 * controller block entity still exists, not that it belongs to the same
 * network. This helper drops such stale references so all three access paths
 * immediately fall back to the empty item handler.
 * <p>
 * The check intentionally runs only when both the interface's and the
 * controller's network ids are known: while a component is still being
 * registered (controller freshly placed or just loaded from NBT without an
 * id) the ids are not final, and dropping the reference then would be wrong.
 */
public final class StorageNetworkSync {

    private StorageNetworkSync() {}

    /**
     * Drops the controller reference of the interface at {@code pos} if that
     * controller no longer belongs to the interface's network. No-op for
     * non-interface block entities, unloaded positions and correct bindings.
     * <p>
     * The capability invalidation that lets cached external consumers re-query
     * is performed by the {@code forgetController} hook in
     * {@code StorageInterfaceEntityMixin}, so callers only need to drop the
     * reference here.
     */
    public static void reconcileController(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return;
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof StorageInterfaceEntity iface)) return;
        reconcileInterface(iface);
    }

    private static void reconcileInterface(StorageInterfaceEntity iface) {
        if (iface.controller == null) return;
        if (!(iface.controller instanceof INetworkComponent controllerNetwork)) return;

        UUID controllerId = controllerNetwork.getStorageNetworkId();
        UUID interfaceId = ((INetworkComponent) iface).getStorageNetworkId();
        if (controllerId != null && interfaceId != null && !controllerId.equals(interfaceId)) {
            iface.forgetController();
        }
    }
}
