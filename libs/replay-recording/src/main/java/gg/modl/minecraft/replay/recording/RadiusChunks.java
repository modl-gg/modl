package gg.modl.minecraft.replay.recording;

public final class RadiusChunks {
    private RadiusChunks() {
    }

    public static int of(int radiusBlocks) {
        return (Math.max(0, radiusBlocks) + 15) >> 4;
    }
}
