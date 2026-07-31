package net.Tetrachlorosilane.createstorageextended.network;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Implemented by any block entity that can be part of a Create: Storage network.
 * Components persist their network UUID in NBT and use it to locate their
 * owning network via {@link StorageNetworkManager}.
 */
public interface INetworkComponent {

    @Nullable
    UUID getStorageNetworkId();

    void setStorageNetworkId(@Nullable UUID networkId);
}
