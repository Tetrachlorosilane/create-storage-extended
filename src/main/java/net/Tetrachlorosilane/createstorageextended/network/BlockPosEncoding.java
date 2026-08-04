package net.Tetrachlorosilane.createstorageextended.network;

import net.minecraft.core.BlockPos;

/**
 * Packed 64-bit coordinate encoding shared by the in-memory topology maps
 * ({@link StorageNetworkManager}) and the NBT serialization format
 * ({@link StorageNetworkData}): {@code x(26 bits) | y(12 bits) | z(26 bits)}.
 * <p>
 * Keeps BFS traversals allocation-free - no temporary {@link BlockPos}
 * objects are created while walking the grid. The sign bit of each axis
 * survives because all decode paths use arithmetic right-shift.
 */
public final class BlockPosEncoding {

    private BlockPosEncoding() {}

    public static long encode(BlockPos pos) {
        return ((long) pos.getX() & 0x3FFFFFFL) << 38
             | ((long) pos.getY() & 0xFFFL) << 26
             | ((long) pos.getZ() & 0x3FFFFFFL);
    }

    public static long encode(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38
             | ((long) y & 0xFFFL) << 26
             | ((long) z & 0x3FFFFFFL);
    }

    public static BlockPos decode(long encoded) {
        return new BlockPos(x(encoded), y(encoded), z(encoded));
    }

    public static int x(long encoded) {
        return (int) (encoded >> 38);
    }

    public static int y(long encoded) {
        return (int) ((encoded << 26) >> 52);
    }

    public static int z(long encoded) {
        return (int) ((encoded << 38) >> 38);
    }
}
