package net.Tetrachlorosilane.createstorageextended.mixin;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes the protected {@link ChunkMap#getChunks()} so the full-network
 * rebuild command can iterate over every currently-loaded chunk.
 */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {

    @Invoker("getChunks")
    Iterable<ChunkHolder> createstorageextended$getChunks();
}
