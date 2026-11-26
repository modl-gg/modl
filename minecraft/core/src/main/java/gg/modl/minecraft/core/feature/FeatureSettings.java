package gg.modl.minecraft.core.feature;

import gg.modl.proto.modl.v1.ReplaySettings;
import lombok.Value;

@Value
public class FeatureSettings {
    static final int MIN_WINDOW_SECONDS = 10;
    static final int MAX_WINDOW_SECONDS = 300;
    static final int MIN_RADIUS_BLOCKS = 16;
    static final int MAX_RADIUS_BLOCKS = 128;
    static final int MIN_MEMORY_BUDGET_MB = 16;
    static final int MAX_MEMORY_BUDGET_MB = 1024;
    static final int MAX_ANTICHEAT_NAME_LENGTH = 32;
    static final int DEFAULT_WINDOW_SECONDS = 60;
    static final int DEFAULT_RADIUS_BLOCKS = 64;
    static final int DEFAULT_MEMORY_BUDGET_MB = 64;
    private static final String DEFAULT_ANTICHEAT_NAME = "Anti-cheat";
    private static final long MEBIBYTE = 1024L * 1024L;
    private static final int HEAP_SHARE_DIVISOR = 4;
    public static final FeatureSettings NONE = new FeatureSettings(false, false, false, false, DEFAULT_WINDOW_SECONDS,
            DEFAULT_RADIUS_BLOCKS, DEFAULT_MEMORY_BUDGET_MB, DEFAULT_ANTICHEAT_NAME);

    boolean replaysEnabled;
    boolean recordEveryPlayer;
    boolean recordOnAnticheatFlag;
    boolean anticheatIntegration;
    int windowSeconds;
    int radiusBlocks;
    int memoryBudgetMb;
    String anticheatName;

    public static FeatureSettings fromProto(ReplaySettings replays) {
        String name = replays.getAnticheatName().trim();
        return new FeatureSettings(replays.getEnabled(), replays.getRecordEveryPlayer(), replays.getRecordOnAnticheatFlag(),
                replays.getAnticheatIntegration(),
                boundedOrDefault(replays.getWindowSeconds(), MIN_WINDOW_SECONDS, MAX_WINDOW_SECONDS, DEFAULT_WINDOW_SECONDS),
                boundedOrDefault(replays.getRadiusBlocks(), MIN_RADIUS_BLOCKS, MAX_RADIUS_BLOCKS, DEFAULT_RADIUS_BLOCKS),
                boundedOrDefault(replays.getMemoryBudgetMb(), MIN_MEMORY_BUDGET_MB, MAX_MEMORY_BUDGET_MB, DEFAULT_MEMORY_BUDGET_MB),
                name.isEmpty() || name.length() > MAX_ANTICHEAT_NAME_LENGTH ? DEFAULT_ANTICHEAT_NAME : name);
    }

    public boolean recordsEveryPlayer() {
        return replaysEnabled && recordEveryPlayer;
    }

    public boolean startsRecordingAtFlags() {
        return replaysEnabled && anticheatIntegration && recordOnAnticheatFlag;
    }

    public long memoryBudgetBytes() {
        return memoryBudgetBytes(Runtime.getRuntime().maxMemory());
    }

    public int effectiveMemoryBudgetMb() {
        return (int) (memoryBudgetBytes() / MEBIBYTE);
    }

    long memoryBudgetBytes(long maxHeapBytes) {
        return Math.min(memoryBudgetMb * MEBIBYTE, maxHeapBytes / HEAP_SHARE_DIVISOR);
    }

    public boolean sameRecorderLimits(FeatureSettings other) {
        return windowSeconds == other.windowSeconds && radiusBlocks == other.radiusBlocks && memoryBudgetMb == other.memoryBudgetMb;
    }

    private static int boundedOrDefault(int value, int min, int max, int fallback) {
        if (value <= 0) return fallback;
        return Math.max(min, Math.min(max, value));
    }
}
