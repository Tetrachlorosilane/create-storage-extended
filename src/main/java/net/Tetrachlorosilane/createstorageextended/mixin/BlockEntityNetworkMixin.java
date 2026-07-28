package net.Tetrachlorosilane.createstorageextended.mixin;

import net.Tetrachlorosilane.createstorageextended.network.INetworkComponent;
import net.Tetrachlorosilane.createstorageextended.network.StorageNetworkManager;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Universal NBT persistence mixin for all network component block entities.
 * <p>
 * This mixin injects into the base {@link BlockEntity#saveAdditional} and
 * {@link BlockEntity#loadAdditional} methods. For any block entity that
 * implements {@link INetworkComponent}, it automatically saves/loads the
 * network UUID.
 * <p>
 * This approach avoids the problem where some network component classes
 * (like StorageControllerEntity and StorageInterfaceEntity) don't override
 * saveAdditional/loadAdditional, which would cause per-class mixin injection
 * to fail.
 */
@Mixin(BlockEntity.class)
public abstract class BlockEntityNetworkMixin {

    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void onSaveAdditional(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        if (this instanceof INetworkComponent component) {
            var id = component.getStorageNetworkId();
            if (id != null) {
                tag.putUUID("StorageNetworkId", id);
            }
        }
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void onLoadAdditional(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        if (this instanceof INetworkComponent component) {
            if (tag.hasUUID("StorageNetworkId")) {
                var id = tag.getUUID("StorageNetworkId");
                component.setStorageNetworkId(id);

                var self = (BlockEntity) (Object) this;
                if (self.getLevel() instanceof ServerLevel serverLevel) {
                    StorageNetworkManager.getInstance().registerComponent(
                            serverLevel, self.getBlockPos(), id);
                }
            }
        }
    }
}
