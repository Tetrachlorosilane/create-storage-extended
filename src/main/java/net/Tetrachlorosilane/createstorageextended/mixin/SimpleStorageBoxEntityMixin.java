package net.Tetrachlorosilane.createstorageextended.mixin;

import net.Tetrachlorosilane.createstorageextended.network.INetworkComponent;
import net.Tetrachlorosilane.createstorageextended.network.StorageNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
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

import java.util.UUID;

/**
 * Mixin into SimpleStorageBoxEntity to implement {@link INetworkComponent}.
 * <p>
 * Since SimpleStorageBoxEntity DOES override saveAdditional/loadAdditional,
 * the NBT injection is kept here. For other network components that don't
 * override these methods, see {@link BlockEntityNetworkMixin}.
 */
@Mixin(targets = "net.fxnt.fxntstorage.simple_storage.SimpleStorageBoxEntity", remap = false)
public abstract class SimpleStorageBoxEntityMixin implements INetworkComponent {

    @Unique
    @Nullable
    private UUID createstorageextended$networkId;

    @Override
    public UUID getStorageNetworkId() {
        return createstorageextended$networkId;
    }

    @Override
    public void setStorageNetworkId(@Nullable UUID networkId) {
        this.createstorageextended$networkId = networkId;
    }

    @Override
    public BlockPos getComponentPos() {
        return ((BlockEntity) (Object) this).getBlockPos();
    }

    @Inject(method = "saveAdditional", at = @At("TAIL"), remap = false)
    private void onSaveAdditional(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        if (createstorageextended$networkId != null) {
            tag.putUUID("StorageNetworkId", createstorageextended$networkId);
        }
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"), remap = false)
    private void onLoadAdditional(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        if (tag.hasUUID("StorageNetworkId")) {
            createstorageextended$networkId = tag.getUUID("StorageNetworkId");

            BlockEntity self = (BlockEntity) (Object) this;
            if (self.getLevel() instanceof ServerLevel serverLevel) {
                StorageNetworkManager.getInstance().registerComponent(
                        serverLevel, self.getBlockPos(), createstorageextended$networkId);
            }
        }
    }

    @Inject(method = "serverTick", at = @At("HEAD"), remap = false)
    private void onServerTick(Level level, BlockPos blockPos, BlockState state, CallbackInfo ci) {
        if (!level.isClientSide() && level instanceof ServerLevel serverLevel && createstorageextended$networkId != null) {
            StorageNetworkManager.getInstance().registerComponent(serverLevel, blockPos, createstorageextended$networkId);
        }
    }
}
