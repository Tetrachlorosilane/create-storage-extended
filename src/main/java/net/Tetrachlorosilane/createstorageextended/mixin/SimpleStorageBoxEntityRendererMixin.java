package net.Tetrachlorosilane.createstorageextended.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.Tetrachlorosilane.createstorageextended.client.RenderCulling;
import net.fxnt.fxntstorage.simple_storage.SimpleStorageBoxEntity;
import net.fxnt.fxntstorage.simple_storage.SimpleStorageBoxEntityRenderer;
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
 * Skips the front-display overlay (item icon, counts, pips) when the box's
 * front face is pressed against a fully opaque block. The front faces of the
 * model itself are culled by the accompanying {@code storage_box_base}
 * resource override, so without this the overlay would render as floating
 * content in front of the neighbouring block.
 * <p>
 * The occlusion test ({@link RenderCulling}) matches the model culler's
 * semantics (full-cube occlusion shape), so both layers agree and no
 * flickering or hover artifacts can occur.
 */
@Mixin(SimpleStorageBoxEntityRenderer.class)
public abstract class SimpleStorageBoxEntityRendererMixin {

    @Inject(method = "render(Lnet/fxnt/fxntstorage/simple_storage/SimpleStorageBoxEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V", at = @At("HEAD"), cancellable = true)
    private void createstorageextended$skipWhenFrontOccluded(SimpleStorageBoxEntity blockEntity, float partialTick,
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
