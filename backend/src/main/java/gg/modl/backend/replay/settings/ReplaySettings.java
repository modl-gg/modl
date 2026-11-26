package gg.modl.backend.replay.settings;

import org.jspecify.annotations.Nullable;

public record ReplaySettings(
    boolean enabled,
    boolean recordEveryPlayer,
    boolean recordOnAnticheatFlag,
    boolean anticheatIntegration,
    int windowSeconds,
    int radiusBlocks,
    int memoryBudgetMb,
    String anticheatName,
    @Nullable Long choiceMadeAt
) {
    public static final int DEFAULT_WINDOW_SECONDS = 60;
    public static final int DEFAULT_RADIUS_BLOCKS = 64;
    public static final int DEFAULT_MEMORY_BUDGET_MB = 64;
    public static final int MIN_WINDOW_SECONDS = 10;
    public static final int MAX_WINDOW_SECONDS = 300;
    public static final int MIN_RADIUS_BLOCKS = 16;
    public static final int MAX_RADIUS_BLOCKS = 128;
    public static final int MIN_MEMORY_BUDGET_MB = 16;
    public static final int MAX_MEMORY_BUDGET_MB = 1024;
    public static final int MAX_ANTICHEAT_NAME_LENGTH = 32;
    public static final String DEFAULT_ANTICHEAT_NAME = "Anti-cheat";
    public static final ReplaySettings DEFAULTS =
        new ReplaySettings(false, true, true, false, DEFAULT_WINDOW_SECONDS, DEFAULT_RADIUS_BLOCKS,
            DEFAULT_MEMORY_BUDGET_MB, DEFAULT_ANTICHEAT_NAME, null);

    public ReplaySettings {
        anticheatName = anticheatName == null ? "" : anticheatName.strip();
    }

    public ReplaySettings normalized() {
        return new ReplaySettings(enabled, recordEveryPlayer, recordOnAnticheatFlag, anticheatIntegration,
            boundedOrDefault(windowSeconds, MIN_WINDOW_SECONDS, MAX_WINDOW_SECONDS, DEFAULT_WINDOW_SECONDS),
            boundedOrDefault(radiusBlocks, MIN_RADIUS_BLOCKS, MAX_RADIUS_BLOCKS, DEFAULT_RADIUS_BLOCKS),
            boundedOrDefault(memoryBudgetMb, MIN_MEMORY_BUDGET_MB, MAX_MEMORY_BUDGET_MB, DEFAULT_MEMORY_BUDGET_MB),
            validAnticheatName(anticheatName) ? anticheatName : DEFAULT_ANTICHEAT_NAME, choiceMadeAt);
    }

    public static boolean validWindow(int seconds) {
        return seconds >= MIN_WINDOW_SECONDS && seconds <= MAX_WINDOW_SECONDS;
    }

    public static boolean validRadius(int blocks) {
        return blocks >= MIN_RADIUS_BLOCKS && blocks <= MAX_RADIUS_BLOCKS;
    }

    public static boolean validMemoryBudget(int megabytes) {
        return megabytes >= MIN_MEMORY_BUDGET_MB && megabytes <= MAX_MEMORY_BUDGET_MB;
    }

    public static boolean validAnticheatName(@Nullable String name) {
        return name != null && !name.isBlank() && name.strip().length() <= MAX_ANTICHEAT_NAME_LENGTH;
    }

    public boolean choiceMade() {
        return choiceMadeAt != null;
    }

    public ReplaySettings chosenAt(long epochMillis) {
        return new ReplaySettings(enabled, recordEveryPlayer, recordOnAnticheatFlag, anticheatIntegration, windowSeconds,
            radiusBlocks, memoryBudgetMb, anticheatName, epochMillis);
    }

    private static int boundedOrDefault(int value, int min, int max, int fallback) {
        if (value == 0) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }
}
