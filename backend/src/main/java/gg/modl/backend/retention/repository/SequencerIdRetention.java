package gg.modl.backend.retention.repository;

import gg.modl.backend.database.InClauseChunks;
import gg.modl.backend.database.Sequencer;
import gg.modl.backend.database.UnitOfWork;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.TableField;

@RequiredArgsConstructor
public final class SequencerIdRetention<R extends Record> implements AgeRetainedRecords {
    private final DSLContext dsl;
    private final UnitOfWork unitOfWork;
    private final TableField<R, Long> id;

    @Override
    public long deleteOldestBatch(Instant createdBefore, int batchSize) {
        long floor = Sequencer.floorAt(createdBefore);
        return unitOfWork.write(() -> deleteOldestBelow(floor, batchSize)).longValue();
    }

    private Integer deleteOldestBelow(long floor, int batchSize) {
        Table<R> table = id.getTable();
        List<Long> oldest = dsl.select(id)
            .from(table)
            .where(id.lt(floor))
            .orderBy(id)
            .limit(batchSize)
            .fetch(id);
        return InClauseChunks.<Long>sum(oldest, chunk -> dsl.deleteFrom(table).where(id.in(chunk)).execute());
    }
}
