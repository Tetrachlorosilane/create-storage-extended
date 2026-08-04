package net.Tetrachlorosilane.createstorageextended.client;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Client-side occlusion helpers for the storage-box face-culling optimization.
 * <p>
 * Mirrors the semantics of the model culler ({@code shouldSideBeRendered} /
 * cullface): a face is hidden only when the neighbouring block's occlusion
 * shape fully covers the whole 1x1x1 cell. Uses {@code getOcclusionShape} +
 * {@code toAabbs}, which are stable across 1.19-1.21 (the dedicated
 * {@code isOcclusionShapeFullBlock} predicate was removed in 1.21).
 */
public final class RenderCulling {

    private RenderCulling() {}

    /**
     * Whether the block in {@code facing} direction from {@code pos} fully
     * occludes a face of the box - i.e. the box's own faces (including the
     * recessed front display) are invisible from outside.
     */
    public static boolean isFrontOccluded(Level level, BlockPos pos, Direction facing) {
        BlockPos neighborPos = pos.relative(facing);
        if (!level.isLoaded(neighborPos)) return false;
        BlockState neighborState = level.getBlockState(neighborPos);
        if (neighborState.isAir()) return false;

        List<AABB> boxes = neighborState.getOcclusionShape(level, neighborPos).toAabbs();
        return boxes.size() == 1 && isFullCube(boxes.getFirst());
    }

    private static boolean isFullCube(AABB box) {
        return box.minX <= 0.0 && box.minY <= 0.0 && box.minZ <= 0.0
            && box.maxX >= 1.0 && box.maxY >= 1.0 && box.maxZ >= 1.0;
    }
}
