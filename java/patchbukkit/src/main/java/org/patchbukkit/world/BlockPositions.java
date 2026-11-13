package org.patchbukkit.world;

/** Packs block positions into one long the same way vanilla's {@code BlockPos#asLong} does. */
public final class BlockPositions {

    private static final int PACKED_X_LENGTH = 26;
    private static final int PACKED_Z_LENGTH = PACKED_X_LENGTH;
    private static final int PACKED_Y_LENGTH = 64 - PACKED_X_LENGTH - PACKED_Z_LENGTH;
    private static final long PACKED_X_MASK = (1L << PACKED_X_LENGTH) - 1L;
    private static final long PACKED_Y_MASK = (1L << PACKED_Y_LENGTH) - 1L;
    private static final long PACKED_Z_MASK = (1L << PACKED_Z_LENGTH) - 1L;
    private static final int Z_OFFSET = PACKED_Y_LENGTH;
    private static final int X_OFFSET = PACKED_Y_LENGTH + PACKED_Z_LENGTH;

    private BlockPositions() {}

    public static long pack(int x, int y, int z) {
        return ((x & PACKED_X_MASK) << X_OFFSET) | ((z & PACKED_Z_MASK) << Z_OFFSET) | (y & PACKED_Y_MASK);
    }

    public static int unpackX(long packed) {
        return (int) (packed << (64 - X_OFFSET - PACKED_X_LENGTH) >> (64 - PACKED_X_LENGTH));
    }

    public static int unpackY(long packed) {
        return (int) (packed << (64 - PACKED_Y_LENGTH) >> (64 - PACKED_Y_LENGTH));
    }

    public static int unpackZ(long packed) {
        return (int) (packed << (64 - Z_OFFSET - PACKED_Z_LENGTH) >> (64 - PACKED_Z_LENGTH));
    }
}
