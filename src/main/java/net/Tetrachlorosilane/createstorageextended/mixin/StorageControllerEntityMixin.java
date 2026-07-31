package net.Tetrachlorosilane.createstorageextended.mixin;

import net.Tetrachlorosilane.createstorageextended.network.INetworkComponent;
import net.Tetrachlorosilane.createstorageextended.network.StorageNetworkManager;
import net.fxnt.fxntstorage.controller.StorageControllerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Mixin into {@link StorageControllerEntity} to implement {@link INetworkComponent}
 * and add persistent network ID support.
 * <p>
 * NBT persistence for network ID is handled by {@link BlockEntityNetworkMixin}
 * to avoid issues with classes that don't override saveAdditional/loadAdditional.
 */
@Mixin(value = StorageControllerEntity.class, remap = false)
public abstract class StorageControllerEntityMixin implements INetworkComponent {

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
        this.createstorageextended$networkId = networkId;
    }

    @Override
    public BlockPos getComponentPos() {
        return ((StorageControllerEntity) (Object) this).getBlockPos();
    }

    @Inject(method = "serverTick", at = @At("HEAD"), remap = false)
    private void onServerTick(Level level, BlockPos blockPos, BlockState state, CallbackInfo ci) {
        if (!createstorageextended$registered && !level.isClientSide() && level instanceof ServerLevel serverLevel && createstorageextended$networkId != null) {
            createstorageextended$registered = true;
            StorageNetworkManager.getInstance().registerComponent(serverLevel, blockPos, createstorageextended$networkId);
        }
    }
}
