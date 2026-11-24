package gg.modl.backend.database;

import java.time.Duration;
import java.time.Instant;
import org.jooq.Field;

public final class TimeBuckets {
    public static final long DAY_MILLIS = Duration.ofDays(1).toMillis();
    public static final long HOUR_MILLIS = Duration.ofHours(1).toMillis();

    private TimeBuckets() {
    }

    public static Field<Long> dayStart(Field<Instant> column) {
        return bucketStart(column, DAY_MILLIS);
    }

    public static Field<Long> hourStart(Field<Instant> column) {
        return bucketStart(column, HOUR_MILLIS);
    }

    private static Field<Long> bucketStart(Field<Instant> column, long width) {
        Field<Long> millis = column.coerce(Long.class);
        return millis.minus(millis.mod(width).plus(width).mod(width));
    }
}
