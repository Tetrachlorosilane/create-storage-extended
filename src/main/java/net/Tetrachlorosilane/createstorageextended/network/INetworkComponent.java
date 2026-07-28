package net.Tetrachlorosilane.createstorageextended.network;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Implemented by any block entity that can be part of a Create: Storage network.
 * <p>
 * Components that implement this interface persist their network UUID in NBT
 * and use it to quickly locate their owning network via {@link StorageNetworkManager}.
 */
public interface INetworkComponent {

    /**
     * @return The UUID of the storage network this component belongs to, or null if not connected.
     */
    @Nullable
    UUID getStorageNetworkId();

    /**
     * Set the network UUID. Implementations should mark themselves dirty.
     */
    void setStorageNetworkId(@Nullable UUID networkId);

    /**
     * @return The BlockPos of this component in the world.
     */
    BlockPos getComponentPos();
}
