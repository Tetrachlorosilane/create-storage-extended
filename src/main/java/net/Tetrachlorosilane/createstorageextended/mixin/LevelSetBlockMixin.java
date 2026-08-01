package net.Tetrachlorosilane.createstorageextended.mixin;

import net.Tetrachlorosilane.createstorageextended.network.StorageNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Hooks into {@code Level.setBlock} to record network-block changes.
 * <p>
 * No topology mutation happens here: the change is only recorded via
 * {@link StorageNetworkManager#markChanged}, and the actual joins, merges and
 * splits are resolved in a single order-independent pass at the next server
 * tick. Deferring the work makes the final topology independent of the order
 * in which a batch of {@code setBlock} calls was processed (bulk placements,
 * contraptions, structure imports, ...).
 * <p>
 * {@code HEAD} captures the old network-block status because the world state
 * at {@code RETURN} no longer contains it. A frame is pushed for every
 * server-side invocation - including no-op frames - so {@code RETURN} pops
 * strictly 1:1 and re-entrant {@code setBlock} calls pair up correctly.
 * The captured state is a plain {@code boolean} and is consumed on the same
 * call; nothing is shared with nested calls.
 */
@Mixin(Level.class)
public abstract class LevelSetBlockMixin {

    @Unique
    private static final TagKey<Block> STORAGE_NETWORK_BLOCK_TAG =
            BlockTags.create(ResourceLocation.fromNamespaceAndPath("fxntstorage", "storage_network_block"));

    @Unique
    private final ThreadLocal<Deque<Boolean>> capturedOldNetStates = ThreadLocal.withInitial(ArrayDeque::new);

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
            at = @At("HEAD"))
    private void captureOldState(BlockPos pos, BlockState newState, int flags, CallbackInfoReturnable<Boolean> cir) {
        Level self = (Level) (Object) this;
        if (self.isClientSide() || !(self instanceof ServerLevel)) return;

        // World state has not changed yet: this is the old block.
        capturedOldNetStates.get().push(self.getBlockState(pos).is(STORAGE_NETWORK_BLOCK_TAG));
    }

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
            at = @At("RETURN"))
    private void onSetBlockReturn(BlockPos pos, BlockState newState, int flags, CallbackInfoReturnable<Boolean> cir) {
        Level self = (Level) (Object) this;
        if (self.isClientSide() || !(self instanceof ServerLevel serverLevel)) return;

        Deque<Boolean> stack = capturedOldNetStates.get();
        Boolean oldIsNet = stack.poll();
        if (oldIsNet == null) return; // defensive; HEAD and RETURN pair 1:1

        // setBlock failed, was cancelled, or did not change the world.
        if (!Boolean.TRUE.equals(cir.getReturnValue())) return;

        boolean newIsNet = newState.is(STORAGE_NETWORK_BLOCK_TAG);

        // Placement, removal or replacement of a network block. Replacement
        // (net -> net) is included because the new block entity needs a
        // network id, which the tick pass assigns.
        if (oldIsNet || newIsNet) {
            StorageNetworkManager.getInstance().markChanged(serverLevel, pos);
        }
    }
}
