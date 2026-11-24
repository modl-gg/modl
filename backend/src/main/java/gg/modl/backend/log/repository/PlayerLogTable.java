package gg.modl.backend.log.repository;

import gg.modl.backend.database.ColumnLookups;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.retention.repository.SequencerIdRetention;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.jooq.TableField;
import org.jooq.TableRecord;

final class PlayerLogTable<R extends TableRecord<R>, T> {
    private static final int INSERT_BATCH_SIZE = 500;

    private final DSLContext dsl;
    private final UnitOfWork unitOfWork;
    private final TableField<R, Long> id;
    private final TableField<R, String> playerId;
    private final TableField<R, Instant> sentAt;
    private final Function<T, R> toRecord;
    private final Function<R, T> toRow;
    private final SequencerIdRetention<R> retention;

    PlayerLogTable(DSLContext dsl, UnitOfWork unitOfWork, TableField<R, Long> id, TableField<R, String> playerId,
                   TableField<R, Instant> sentAt, Function<T, R> toRecord, Function<R, T> toRow) {
        this.dsl = dsl;
        this.unitOfWork = unitOfWork;
        this.id = id;
        this.playerId = playerId;
        this.sentAt = sentAt;
        this.toRecord = toRecord;
        this.toRow = toRow;
        this.retention = new SequencerIdRetention<>(dsl, unitOfWork, id);
    }

    void insertAll(List<T> rows) {
        if (rows.isEmpty()) {
            return;
        }
        unitOfWork.write(() -> {
            List<R> records = rows.stream().map(toRecord).sorted(Comparator.comparing(row -> row.get(id))).toList();
            for (int start = 0; start < records.size(); start += INSERT_BATCH_SIZE) {
                dsl.batchInsert(records.subList(start, Math.min(start + INSERT_BATCH_SIZE, records.size()))).execute();
            }
        });
    }

    List<T> findRecentByPlayer(String player, int limit) {
        return dsl.selectFrom(id.getTable())
            .where(ColumnLookups.matches(playerId, player))
            .orderBy(sentAt.desc(), id.desc())
            .limit(limit)
            .fetch(toRow::apply);
    }

    long deleteOldestBatch(Instant createdBefore, int batchSize) {
        return retention.deleteOldestBatch(createdBefore, batchSize);
    }
}
