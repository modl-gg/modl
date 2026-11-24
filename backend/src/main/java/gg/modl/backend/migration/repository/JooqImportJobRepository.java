package gg.modl.backend.migration.repository;

import static gg.modl.backend.database.jooq.Tables.IMPORT_JOBS;

import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.jooq.tables.records.ImportJobsRecord;
import gg.modl.backend.migration.data.ExportCounts;
import gg.modl.backend.migration.data.ImportEnd;
import gg.modl.backend.migration.data.ImportEnding;
import gg.modl.backend.migration.data.ImportState;
import gg.modl.backend.migration.data.ImportedCounts;
import gg.modl.backend.migration.data.IpHistoryRetention;
import gg.modl.backend.migration.data.MigrationStatus;
import gg.modl.backend.migration.data.ScopedPunishments;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SortField;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JooqImportJobRepository implements ImportJobRepository {
    private final DSLContext dsl;

    @Override
    public void insert(MigrationStatus job) {
        ImportJobsRecord row = new ImportJobsRecord();
        row.setId(TextLimits.require(IMPORT_JOBS.ID, job.getId()));
        row.setType(TextLimits.require(IMPORT_JOBS.TYPE, job.getType()));
        row.setStatus(storedState(job.getStatus()));
        row.setStartedFrom(TextLimits.requireIfPresent(IMPORT_JOBS.STARTED_FROM, job.getStartedFrom()));
        row.setStartedAddress(TextLimits.requireIfPresent(IMPORT_JOBS.STARTED_ADDRESS, job.getStartedAddress()));
        row.setScopedPunishments(TextLimits.requireIfPresent(IMPORT_JOBS.SCOPED_PUNISHMENTS, nameOf(job.getScopedPunishments())));
        row.setIpHistory(TextLimits.requireIfPresent(IMPORT_JOBS.IP_HISTORY, nameOf(job.getIpHistory())));
        row.setRecordsProcessed(job.getRecordsProcessed());
        row.setRecordsSkipped(job.getRecordsSkipped());
        row.setTotalRecords(job.getTotalRecords());
        row.setExportFailed(job.getExportFailed());
        row.setExportSkipped(job.getExportSkipped());
        row.setEndReason(storedEnding(job.getEnding()));
        row.setErrorMessage(job.getError());
        row.setStartedAt(job.getStartedAt());
        row.setLastProgressAt(job.getLastProgressAt() != null ? job.getLastProgressAt() : job.getStartedAt());
        row.setCompletedAt(job.getCompletedAt());
        dsl.insertInto(IMPORT_JOBS).set(row).execute();
    }

    @Override
    public Optional<MigrationStatus> findById(String id) {
        if (!TextLimits.canMatch(IMPORT_JOBS.ID, id)) {
            return Optional.empty();
        }
        return dsl.selectFrom(IMPORT_JOBS)
            .where(IMPORT_JOBS.ID.eq(id))
            .fetchOptional()
            .map(JooqImportJobRepository::toJob);
    }

    @Override
    public Optional<MigrationStatus> findLatest() {
        return findNewest(DSL.noCondition());
    }

    @Override
    public Optional<MigrationStatus> findActive() {
        return findNewest(inStates(ImportState.ACTIVE));
    }

    @Override
    public boolean transition(String id, ImportState from, ImportState to, Instant at) {
        return update(id, List.of(from), changesTo(to, at));
    }

    @Override
    public boolean refreshLease(String id, ImportState state, Instant at) {
        return update(id, List.of(state), changesTo(state, at));
    }

    @Override
    public boolean recordExport(String id, ImportState to, ExportCounts counts, Instant at) {
        Map<Field<?>, Object> changes = changesTo(to, at);
        putIfReported(changes, IMPORT_JOBS.RECORDS_PROCESSED, counts.processed());
        putIfReported(changes, IMPORT_JOBS.TOTAL_RECORDS, counts.total());
        putIfReported(changes, IMPORT_JOBS.EXPORT_FAILED, counts.failed());
        putIfReported(changes, IMPORT_JOBS.EXPORT_SKIPPED, counts.skipped());
        return update(id, List.of(ImportState.BUILDING), changes);
    }

    @Override
    public boolean beginProcessing(String id, ImportState from, Instant at) {
        return update(id, List.of(from), countsTo(ImportState.PROCESSING, ImportedCounts.NONE, at));
    }

    @Override
    public boolean recordProcessed(String id, ImportedCounts counts, Instant at) {
        return update(id, List.of(ImportState.PROCESSING), countsTo(ImportState.PROCESSING, counts, at));
    }

    @Override
    public boolean complete(String id, ImportedCounts counts, Instant at) {
        Map<Field<?>, Object> changes = countsTo(ImportState.COMPLETED, counts, at);
        changes.put(IMPORT_JOBS.COMPLETED_AT, at);
        return update(id, List.of(ImportState.PROCESSING), changes);
    }

    @Override
    public boolean end(String id, Collection<ImportState> from, ImportEnd end) {
        return update(id, from, endChanges(end));
    }

    @Override
    public long failStale(ImportState state, Instant staleBefore, ImportEnd end) {
        return dsl.update(IMPORT_JOBS)
            .set(endChanges(end))
            .where(inStates(List.of(state)).and(lastActivity().lt(staleBefore).or(lastActivity().isNull())))
            .execute();
    }

    private boolean update(String id, Collection<ImportState> from, Map<Field<?>, Object> changes) {
        return dsl.update(IMPORT_JOBS)
            .set(changes)
            .where(IMPORT_JOBS.ID.eq(id).and(inStates(from)))
            .execute() > 0;
    }

    private Optional<MigrationStatus> findNewest(Condition condition) {
        return dsl.selectFrom(IMPORT_JOBS)
            .where(condition)
            .orderBy(newestFirst())
            .limit(1)
            .fetchOptional()
            .map(JooqImportJobRepository::toJob);
    }

    private static List<SortField<?>> newestFirst() {
        return List.of(IMPORT_JOBS.STARTED_AT.desc().nullsLast(), IMPORT_JOBS.ID.desc());
    }

    private static Condition inStates(Collection<ImportState> states) {
        return IMPORT_JOBS.STATUS.in(states.stream().map(ImportState::storedValue).toList());
    }

    private static Field<Instant> lastActivity() {
        return DSL.coalesce(IMPORT_JOBS.LAST_PROGRESS_AT, IMPORT_JOBS.STARTED_AT);
    }

    private static Map<Field<?>, Object> changesTo(ImportState state, Instant at) {
        Map<Field<?>, Object> changes = new LinkedHashMap<>();
        changes.put(IMPORT_JOBS.STATUS, storedState(state));
        changes.put(IMPORT_JOBS.LAST_PROGRESS_AT, at);
        return changes;
    }

    private static Map<Field<?>, Object> countsTo(ImportState state, ImportedCounts counts, Instant at) {
        Map<Field<?>, Object> changes = changesTo(state, at);
        changes.put(IMPORT_JOBS.RECORDS_PROCESSED, counts.processed());
        changes.put(IMPORT_JOBS.RECORDS_SKIPPED, counts.skipped());
        putIfReported(changes, IMPORT_JOBS.TOTAL_RECORDS, counts.total());
        return changes;
    }

    private static Map<Field<?>, Object> endChanges(ImportEnd end) {
        Map<Field<?>, Object> changes = new LinkedHashMap<>();
        changes.put(IMPORT_JOBS.STATUS, storedState(ImportState.FAILED));
        changes.put(IMPORT_JOBS.END_REASON, storedEnding(end.ending()));
        changes.put(IMPORT_JOBS.ERROR_MESSAGE, end.detail());
        changes.put(IMPORT_JOBS.COMPLETED_AT, end.at());
        return changes;
    }

    private static void putIfReported(Map<Field<?>, Object> changes, Field<Integer> field, @Nullable Integer count) {
        if (count != null) {
            changes.put(field, count);
        }
    }

    private static String storedState(ImportState state) {
        return TextLimits.require(IMPORT_JOBS.STATUS, state.storedValue());
    }

    @Nullable
    private static String storedEnding(@Nullable ImportEnding ending) {
        return TextLimits.requireIfPresent(IMPORT_JOBS.END_REASON, nameOf(ending));
    }

    @Nullable
    private static String nameOf(@Nullable Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static MigrationStatus toJob(ImportJobsRecord row) {
        return MigrationStatus.builder()
            .id(row.getId())
            .type(row.getType())
            .status(ImportState.fromStored(row.getStatus()))
            .recordsProcessed(row.getRecordsProcessed())
            .recordsSkipped(row.getRecordsSkipped())
            .totalRecords(row.getTotalRecords())
            .exportFailed(row.getExportFailed())
            .exportSkipped(row.getExportSkipped())
            .startedFrom(row.getStartedFrom())
            .startedAddress(row.getStartedAddress())
            .scopedPunishments(valueOf(ScopedPunishments.class, row.getScopedPunishments()))
            .ipHistory(valueOf(IpHistoryRetention.class, row.getIpHistory()))
            .ending(valueOf(ImportEnding.class, row.getEndReason()))
            .error(row.getErrorMessage())
            .startedAt(row.getStartedAt())
            .lastProgressAt(row.getLastProgressAt())
            .completedAt(row.getCompletedAt())
            .build();
    }

    @Nullable
    private static <E extends Enum<E>> E valueOf(Class<E> type, @Nullable String stored) {
        return stored == null ? null : Enum.valueOf(type, stored);
    }
}
