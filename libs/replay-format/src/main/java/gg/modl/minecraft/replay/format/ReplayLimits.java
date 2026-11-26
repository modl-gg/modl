package gg.modl.minecraft.replay.format;

public final class ReplayLimits {
    public static final long MAX_COMPRESSED_BYTES = 10L * 1024 * 1024;
    public static final long MAX_UNCOMPRESSED_BYTES = 256L * 1024 * 1024;
    public static final int MAX_FRAME_BYTES = 4 * 1024 * 1024;
    public static final int MAX_SECTIONS = 65_536;
    public static final long MAX_EVENTS = 2_000_000L;
    public static final int MAX_KEYFRAMES = 4_096;
    public static final int MAX_BLOCK_STATES = 131_072;
    public static final int MAX_STRINGS = 131_072;
    public static final int MAX_BLOCK_ENTITIES = 65_536;
    public static final int SECTION_STATES = 4_096;

    private ReplayLimits() {
    }
}
