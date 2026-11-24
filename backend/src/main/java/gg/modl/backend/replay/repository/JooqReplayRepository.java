package gg.modl.backend.replay.repository;

import static gg.modl.backend.database.jooq.Tables.REPLAYS;

import gg.modl.backend.database.InClauseChunks;
import gg.modl.backend.database.JsonColumns;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.jooq.tables.records.ReplaysRecord;
import gg.modl.backend.replay.data.ReplayDocument;
import gg.modl.backend.replay.data.ReplayLabel;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

@Repository
@RequiredArgsConstructor
public class JooqReplayRepository implements ReplayRepository {
    private static final int MAX_LISTED = 100;
    private static final int MAX_EXPIRED_PAGE = 500;
    private static final TypeReference<List<ReplayLabel>> LABELS = new TypeReference<>() {
    };
    private static final List<Field<?>> COLUMNS_WITHOUT_LABELS = List.of(REPLAYS.ID, REPLAYS.MC_VERSION, REPLAYS.TARGET_UUID,
        REPLAYS.TARGET_NAME, REPLAYS.FILE_SIZE, REPLAYS.STORAGE_KEY, REPLAYS.STATUS, REPLAYS.CREATED_AT);

    private final DSLContext dsl;
    private final JsonColumns jsonColumns;

    @Override
    public void insert(ReplayDocument replay) {
        ReplaysRecord row = new ReplaysRecord();
        row.setId(TextLimits.require(REPLAYS.ID, replay.getId()));
        row.setMcVersion(TextLimits.requireIfPresent(REPLAYS.MC_VERSION, replay.getMcVersion()));
        row.setTargetUuid(TextLimits.requireIfPresent(REPLAYS.TARGET_UUID, replay.getTargetUuid()));
        row.setTargetName(TextLimits.requireIfPresent(REPLAYS.TARGET_NAME, replay.getTargetName()));
        row.setFileSize(replay.getFileSize());
        row.setStorageKey(TextLimits.requireIfPresent(REPLAYS.STORAGE_KEY, replay.getStorageKey()));
        row.setStatus(TextLimits.require(REPLAYS.STATUS, replay.getStatus()));
        row.setLabelsJson(jsonColumns.writeIfPresent(replay.getLabels()));
        row.setCreatedAt(replay.getCreatedAt());
        dsl.insertInto(REPLAYS).set(row).execute();
    }

    @Override
    public boolean updateUpload(String replayId, String status, long fileSize) {
        return dsl.update(REPLAYS)
            .set(REPLAYS.STATUS, TextLimits.require(REPLAYS.STATUS, status))
            .set(REPLAYS.FILE_SIZE, fileSize)
            .where(REPLAYS.ID.eq(replayId))
            .execute() > 0;
    }

    @Override
    public Optional<ReplayDocument> findById(String replayId) {
        return dsl.selectFrom(REPLAYS)
            .where(REPLAYS.ID.eq(replayId))
            .fetchOptional()
            .map(this::toReplayWithLabels);
    }

    @Override
    public Optional<ReplayDocument> replaceLabels(String replayId, List<ReplayLabel> labels) {
        dsl.update(REPLAYS)
            .set(REPLAYS.LABELS_JSON, jsonColumns.write(labels))
            .where(REPLAYS.ID.eq(replayId))
            .execute();
        return findById(replayId);
    }

    @Override
    public List<ReplayDocument> findByTargetUuid(String targetUuid, int limit) {
        return dsl.select(COLUMNS_WITHOUT_LABELS)
            .from(REPLAYS)
            .where(REPLAYS.TARGET_UUID.eq(targetUuid))
            .orderBy(REPLAYS.CREATED_AT.desc(), REPLAYS.ID.desc())
            .limit(Math.min(limit, MAX_LISTED))
            .fetch()
            .map(JooqReplayRepository::toReplay);
    }

    @Override
    public List<ReplayDocument> findExpiredWithStorageKey(Instant cutoff, @Nullable ReplayCursor after, int limit) {
        return findExpiredPage(REPLAYS.STORAGE_KEY.isNotNull().and(REPLAYS.STORAGE_KEY.ne("")), cutoff, after, limit);
    }

    @Override
    public List<ReplayDocument> findExpiredWithMissingStorageKey(Instant cutoff, @Nullable ReplayCursor after, int limit) {
        return findExpiredPage(REPLAYS.STORAGE_KEY.isNull().or(REPLAYS.STORAGE_KEY.eq("")), cutoff, after, limit);
    }

    @Override
    public List<ReplayDocument> findByStorageKeys(Collection<String> storageKeys) {
        return InClauseChunks.<String, ReplayDocument>collect(storageKeys, chunk -> dsl.select(COLUMNS_WITHOUT_LABELS)
            .from(REPLAYS)
            .where(REPLAYS.STORAGE_KEY.in(chunk))
            .orderBy(REPLAYS.ID)
            .fetch()
            .map(JooqReplayRepository::toReplay));
    }

    @Override
    public long deleteByIds(Collection<String> replayIds) {
        return InClauseChunks.<String>sum(replayIds.stream().sorted().toList(),
            chunk -> dsl.deleteFrom(REPLAYS).where(REPLAYS.ID.in(chunk)).execute());
    }

    private List<ReplayDocument> findExpiredPage(Condition storage, Instant cutoff, @Nullable ReplayCursor after, int limit) {
        Condition expired = REPLAYS.STATUS.in(ReplayDocument.STATUS_COMPLETE, ReplayDocument.STATUS_FAILED)
            .and(REPLAYS.CREATED_AT.lt(cutoff));
        Condition remaining = after == null
            ? DSL.noCondition()
            : DSL.row(REPLAYS.CREATED_AT, REPLAYS.ID).gt(after.createdAt(), after.id());
        return dsl.select(COLUMNS_WITHOUT_LABELS)
            .from(REPLAYS)
            .where(expired.and(storage).and(remaining))
            .orderBy(REPLAYS.CREATED_AT, REPLAYS.ID)
            .limit(Math.min(limit, MAX_EXPIRED_PAGE))
            .fetch()
            .map(JooqReplayRepository::toReplay);
    }

    private ReplayDocument toReplayWithLabels(ReplaysRecord row) {
        ReplayDocument replay = toReplay(row);
        replay.setLabels(jsonColumns.read(row.getLabelsJson(), LABELS));
        return replay;
    }

    private static ReplayDocument toReplay(Record row) {
        ReplayDocument replay = new ReplayDocument();
        replay.setId(row.get(REPLAYS.ID));
        replay.setMcVersion(row.get(REPLAYS.MC_VERSION));
        replay.setTargetUuid(row.get(REPLAYS.TARGET_UUID));
        replay.setTargetName(row.get(REPLAYS.TARGET_NAME));
        replay.setFileSize(row.get(REPLAYS.FILE_SIZE));
        replay.setStorageKey(row.get(REPLAYS.STORAGE_KEY));
        replay.setStatus(row.get(REPLAYS.STATUS));
        replay.setCreatedAt(row.get(REPLAYS.CREATED_AT));
        return replay;
    }
}
