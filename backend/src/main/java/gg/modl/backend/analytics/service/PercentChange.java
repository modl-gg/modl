package gg.modl.backend.analytics.service;

public final class PercentChange {
    private static final int PERCENT = 100;

    private PercentChange() {
    }

    public static int between(long previous, long current) {
        return previous > 0 ? (int) Math.round((double) (current - previous) / previous * PERCENT) : 0;
    }
}
