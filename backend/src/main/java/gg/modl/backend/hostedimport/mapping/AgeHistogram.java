package gg.modl.backend.hostedimport.mapping;

import java.time.Duration;
import java.time.Instant;

public final class AgeHistogram {
    public static final int MAX_DAYS = 3651;

    private final Instant reference;
    private final long[] countsByAgeDays = new long[MAX_DAYS + 1];
    private long total;
    private int oldestDays = -1;

    public AgeHistogram(Instant reference) {
        this.reference = reference;
    }

    public void add(Instant at) {
        long days = Math.max(0, Duration.between(at, reference).toDays());
        int bucket = (int) Math.min(days, MAX_DAYS);
        countsByAgeDays[bucket]++;
        total++;
        oldestDays = Math.max(oldestDays, bucket);
    }

    public long total() {
        return total;
    }

    public int oldestAgeDays() {
        return Math.max(0, oldestDays);
    }

    public boolean isEmpty() {
        return total == 0;
    }

    public long olderThan(int keptDays) {
        long older = 0;
        for (int days = Math.max(0, keptDays); days <= MAX_DAYS; days++) {
            older += countsByAgeDays[days];
        }
        return older;
    }
}
