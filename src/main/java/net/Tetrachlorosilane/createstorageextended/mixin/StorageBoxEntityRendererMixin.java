package net.Tetrachlorosilane.createstorageextended.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.Tetrachlorosilane.createstorageextended.client.RenderCulling;
import net.fxnt.fxntstorage.container.StorageBoxEntity;
import net.fxnt.fxntstorage.container.StorageBoxEntityRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.Direction;
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
 * of {@code render}. Skips the front overlay when the front neighbour is a
 * fully opaque block; the model faces are culled by the shared
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
        if (RenderCulling.isFrontOccluded(level, blockEntity.getBlockPos(), facing)) {
            ci.cancel();
        }
    }
}
