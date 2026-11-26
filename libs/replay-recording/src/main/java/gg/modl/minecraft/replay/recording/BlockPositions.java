package gg.modl.minecraft.replay.recording;

final class BlockPositions {
    private BlockPositions() {
    }

    static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    static int x(long packed) {
        return (int) (packed >> 38);
    }

    static int y(long packed) {
        return (int) (packed << 52 >> 52);
    }

    static int z(long packed) {
        return (int) (packed << 26 >> 38);
    }

    static long chunkKey(int segment, int chunkX, int chunkZ) {
        return ((long) (segment & 0xFFFF) << 48) | ((long) (chunkX & 0xFFFFFF) << 24) | (chunkZ & 0xFFFFFF);
    }

    static int chunkSegment(long key) {
        return (int) (key >>> 48);
    }

    static int chunkX(long key) {
        return (int) (key << 16 >> 40);
    }

    static int chunkZ(long key) {
        return (int) (key << 40 >> 40);
    }
}
