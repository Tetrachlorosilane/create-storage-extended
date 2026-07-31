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
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/**
 * Hooks into {@code Level.setBlock} to handle both removal and placement
 * of storage network blocks.
 * <p>
 * By comparing old/new states this single injection replaces all per-event
 * listeners for block movement — contraption assembly/disassembly, player
 * breaking/placing, explosions, pistons, and third-party mods.
 */
@Mixin(Level.class)
public abstract class LevelSetBlockMixin {

    @Unique
    private static final TagKey<Block> STORAGE_NETWORK_BLOCK_TAG =
            BlockTags.create(ResourceLocation.fromNamespaceAndPath("fxntstorage", "storage_network_block"));

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
            at = @At("HEAD"))
    private void onSetBlock(BlockPos pos, BlockState newState, int flags, CallbackInfoReturnable<Boolean> cir) {
        Level self = (Level) (Object) this;
        if (self.isClientSide() || !(self instanceof ServerLevel serverLevel)) return;

        BlockState oldState = self.getBlockState(pos);
        boolean oldIsNet = oldState.is(STORAGE_NETWORK_BLOCK_TAG);
        boolean newIsNet = newState.is(STORAGE_NETWORK_BLOCK_TAG);

        if (oldIsNet == newIsNet) return; // same type of block, no topology change

        if (oldIsNet) {
            // Network block removed (→ air, contraption capture, etc.)
            UUID networkId = StorageNetworkManager.getInstance().getNetworkId(serverLevel, pos);
            if (networkId != null) {
                StorageNetworkManager.getInstance().onBlockRemoved(serverLevel, pos, networkId);
            }
        } else {
            // Network block placed (from contraption, player, etc.)
            BlockEntity be = self.getBlockEntity(pos);
            UUID existingId = null;
            if (be instanceof INetworkComponent component) {
                existingId = component.getStorageNetworkId();
            }
            UUID newId = StorageNetworkManager.getInstance()
                    .onBlockPlaced(serverLevel, pos, existingId);
            if (newId != null && be instanceof INetworkComponent component) {
                component.setStorageNetworkId(newId);
                be.setChanged();
            }
        }
    }
}
