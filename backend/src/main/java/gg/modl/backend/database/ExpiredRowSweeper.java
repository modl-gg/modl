package gg.modl.backend.database;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.RowN;
import org.jooq.impl.DSL;

@Slf4j
public final class ExpiredRowSweeper {
    public static final int BATCH_SIZE = 500;

    private final List<ExpiringRows> expiringRows;
    private final UnitOfWork unitOfWork;
    private final DSLContext dsl;
    private final Clock clock;

    public ExpiredRowSweeper(List<ExpiringRows> expiringRows, UnitOfWork unitOfWork, DSLContext dsl, Clock clock) {
        this.expiringRows = List.copyOf(expiringRows);
        this.unitOfWork = unitOfWork;
        this.dsl = dsl;
        this.clock = clock;
    }

    public long sweep() {
        Instant now = clock.instant();
        long deleted = 0;
        for (ExpiringRows rows : expiringRows) {
            deleted += sweep(rows, now);
        }
        if (deleted > 0) {
            log.debug("Deleted {} expired rows", deleted);
        }
        return deleted;
    }

    private long sweep(ExpiringRows rows, Instant now) {
        if (!hasExpired(rows, now)) {
            return 0;
        }
        long deleted = 0;
        int batch;
        do {
            batch = unitOfWork.write(() -> deleteBatch(rows, now));
            deleted += batch;
        } while (batch == BATCH_SIZE);
        return deleted;
    }

    private boolean hasExpired(ExpiringRows rows, Instant now) {
        return unitOfWork.read(() -> dsl.fetchExists(rows.table(), rows.expiresAt().lt(now)));
    }

    private int deleteBatch(ExpiringRows rows, Instant now) {
        List<Field<?>> key = rows.key();
        List<RowN> expired = dsl.select(key)
            .from(rows.table())
            .where(rows.expiresAt().lt(now))
            .orderBy(rows.expiresAt())
            .limit(BATCH_SIZE)
            .fetch()
            .map(ExpiredRowSweeper::keyRow);
        if (expired.isEmpty()) {
            return 0;
        }
        dsl.deleteFrom(rows.table())
            .where(DSL.row(key).in(expired))
            .and(rows.expiresAt().lt(now))
            .execute();
        return expired.size();
    }

    private static RowN keyRow(Record key) {
        return DSL.row(key.intoArray());
    }
}
