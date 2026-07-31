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
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/**
 * Reconciles persisted storage-network topology after successful block changes.
 *
 * <p>The hook runs at {@code RETURN}, so failed or cancelled {@code setBlock}
 * calls cannot mutate topology. It reads both the final world state and the
 * current persisted membership instead of carrying mutable state across the
 * call. This makes nested and re-entrant {@code setBlock} calls idempotent:
 * every return simply repairs any mismatch that still exists at its position.</p>
 */
@Mixin(Level.class)
public abstract class LevelSetBlockMixin {

    @Unique
    private static final TagKey<Block> STORAGE_NETWORK_BLOCK_TAG =
            BlockTags.create(ResourceLocation.fromNamespaceAndPath("fxntstorage", "storage_network_block"));

    @Unique
    private final ThreadLocal<Deque<Boolean>> relevantCalls = ThreadLocal.withInitial(ArrayDeque::new);

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
            at = @At("HEAD"))
    private void captureRelevance(BlockPos pos, BlockState newState, int flags,
                                  CallbackInfoReturnable<Boolean> cir) {
        Level self = (Level) (Object) this;
        if (self.isClientSide() || !(self instanceof ServerLevel)) return;

        boolean oldIsNetworkBlock = self.getBlockState(pos).is(STORAGE_NETWORK_BLOCK_TAG);
        boolean requestedIsNetworkBlock = newState.is(STORAGE_NETWORK_BLOCK_TAG);

        // Push one frame for every invocation. A nested no-op call must not
        // consume the relevance marker belonging to its outer caller.
        relevantCalls.get().push(oldIsNetworkBlock || requestedIsNetworkBlock);
    }

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
            at = @At("RETURN"))
    private void reconcileTopology(BlockPos pos, BlockState newState, int flags,
                                   CallbackInfoReturnable<Boolean> cir) {
        Level self = (Level) (Object) this;
        if (self.isClientSide() || !(self instanceof ServerLevel serverLevel)) return;

        Deque<Boolean> stack = relevantCalls.get();
        Boolean relevant = stack.poll();
        if (stack.isEmpty()) {
            relevantCalls.remove();
        }

        if (!Boolean.TRUE.equals(relevant) || !Boolean.TRUE.equals(cir.getReturnValue())) return;

        StorageNetworkManager manager = StorageNetworkManager.getInstance();
        UUID persistedId = manager.getNetworkId(serverLevel, pos);
        boolean finalIsNetworkBlock = self.getBlockState(pos).is(STORAGE_NETWORK_BLOCK_TAG);

        if (!finalIsNetworkBlock) {
            if (persistedId != null) {
                manager.onBlockRemoved(serverLevel, pos, persistedId);
            }
            return;
        }

        BlockEntity blockEntity = self.getBlockEntity(pos);

        // The position is already authoritative in SavedData. This also covers
        // network-to-network replacement: copy the existing UUID to the new BE.
        if (persistedId != null) {
            assignNetworkId(blockEntity, persistedId);
            return;
        }

        UUID existingId = null;
        if (blockEntity instanceof INetworkComponent component) {
            existingId = component.getStorageNetworkId();
        }

        UUID newId = manager.onBlockPlaced(serverLevel, pos, existingId);
        assignNetworkId(blockEntity, newId);
    }

    @Unique
    private static void assignNetworkId(@Nullable BlockEntity blockEntity, @Nullable UUID networkId) {
        if (networkId == null) return;

        if (blockEntity instanceof INetworkComponent component) {
            component.setStorageNetworkId(networkId);
            blockEntity.setChanged();
        }
    }
}
