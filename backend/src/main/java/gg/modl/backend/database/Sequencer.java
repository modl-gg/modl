package gg.modl.backend.database;

import static gg.modl.backend.database.jooq.Tables.AUDIT_LOGS;
import static gg.modl.backend.database.jooq.Tables.CHAT_LOGS;
import static gg.modl.backend.database.jooq.Tables.COMMAND_LOGS;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

public final class Sequencer {
    private static final int MILLIS_SHIFT = 20;

    private final Clock clock;
    private final AtomicLong last;

    Sequencer(Clock clock, long last) {
        this.clock = clock;
        this.last = new AtomicLong(last);
    }

    public static Sequencer seed(DSLContext dsl, Clock clock) {
        long highestId = List.of(CHAT_LOGS.ID, COMMAND_LOGS.ID, AUDIT_LOGS.ID).stream()
            .map(id -> dsl.select(DSL.max(id)).from(id.getTable()).fetchOne(0, Long.class))
            .filter(Objects::nonNull)
            .mapToLong(Long::longValue)
            .max()
            .orElse(0L);
        return new Sequencer(clock, Math.max(floorAt(clock.instant()), highestId + 1));
    }

    public long next() {
        long floor = floorAt(clock.instant());
        return last.updateAndGet(previous -> Math.max(previous + 1, floor));
    }

    public static long floorAt(Instant instant) {
        return instant.toEpochMilli() << MILLIS_SHIFT;
    }
}
