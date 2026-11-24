package gg.modl.backend.ai.model;

public record AiUsageLimits(
    int callsPerHour,
    int callsPerDay,
    int reportsPerReporterPerHour,
    int concurrentCalls,
    int maxOutputTokens
) {
    public static final AiUsageLimits DEFAULTS = new AiUsageLimits(60, 500, 5, 2, 4096);

    public boolean withinBounds() {
        return within(callsPerHour, 1, 10_000)
            && within(callsPerDay, 1, 100_000)
            && within(reportsPerReporterPerHour, 1, 1_000)
            && within(concurrentCalls, 1, 16)
            && within(maxOutputTokens, 256, 32_768);
    }

    private static boolean within(int value, int min, int max) {
        return value >= min && value <= max;
    }
}
