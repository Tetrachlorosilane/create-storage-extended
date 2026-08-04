package net.Tetrachlorosilane.createstorageextended.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.createmod.catnip.gui.AbstractSimiScreen;
import net.Tetrachlorosilane.createstorageextended.client.RenderCulling;
import net.fxnt.fxntstorage.container.StorageBoxEntity;
import net.fxnt.fxntstorage.container.StorageBoxEntityRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counterpart of {@link SimpleStorageBoxEntityRendererMixin} for the Storage
 * Box renderer, which overrides Create's {@code renderSafe} template instead
 * of {@code render}. Skips the front overlay when it cannot be seen: the
 * front neighbour is a fully opaque block, or the player stands behind the
 * box; the model faces are culled by the shared
 * {@code storage_box_base} resource override.
 */
@Mixin(StorageBoxEntityRenderer.class)
public abstract class StorageBoxEntityRendererMixin {

    @Inject(method = "renderSafe(Lnet/fxnt/fxntstorage/container/StorageBoxEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V", at = @At("HEAD"), cancellable = true)
    private void createstorageextended$skipWhenFrontOccluded(StorageBoxEntity blockEntity, float partialTick,
                                                             PoseStack poseStack, MultiBufferSource buffer,
                                                             int packedLight, int packedOverlay, CallbackInfo ci) {
        Level level = blockEntity.getLevel();
        if (level == null) return;

        BlockState state = blockEntity.getBlockState();
        Direction facing = state.getValue(HorizontalDirectionalBlock.FACING);
        BlockPos pos = blockEntity.getBlockPos();

        Player player = Minecraft.getInstance().player;
        if (player == null) return;

        // Ponder scenes render the overlay with a forced close distance; the
        // virtual camera distance is meaningless there, so never cull.
        if (Minecraft.getInstance().screen instanceof AbstractSimiScreen) return;

        // Skip when the front display cannot be seen: farther than one chunk,
        // fully occluded by a neighbouring block, or the player stands behind
        // the box (the box geometry hides the front from any view direction).
        boolean tooFar = pos.distToCenterSqr(player.position()) > RenderCulling.maxOverlayDistanceSq();
        boolean behind = RenderCulling.isBehind(player.position(), pos, facing);
        if (tooFar || behind || RenderCulling.isFrontOccluded(level, pos, facing)) {
            ci.cancel();
        }
    }
}
