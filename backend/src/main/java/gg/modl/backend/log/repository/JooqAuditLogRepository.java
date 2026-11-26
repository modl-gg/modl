package gg.modl.backend.log.repository;

import static gg.modl.backend.database.jooq.Tables.AUDIT_LOGS;

import gg.modl.backend.audit.data.AuditLog;
import gg.modl.backend.database.ColumnLookups;
import gg.modl.backend.database.JsonColumns;
import gg.modl.backend.database.Sequencer;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.database.jooq.tables.records.AuditLogsRecord;
import gg.modl.backend.retention.repository.SequencerIdRetention;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
public class JooqAuditLogRepository implements AuditLogRepository {
    private static final String LEVEL_INFO = "info";
    private static final List<String> EVIDENCE_WORDS = List.of("evidence", "upload", "file");
    private static final List<String> INFO_EVIDENCE_WORDS = List.of("uploaded", "attachment");

    private final DSLContext dsl;
    private final UnitOfWork unitOfWork;
    private final Sequencer sequencer;
    private final JsonColumns jsonColumns;
    private final SequencerIdRetention<AuditLogsRecord> retention;

    public JooqAuditLogRepository(DSLContext dsl, UnitOfWork unitOfWork, Sequencer sequencer, JsonColumns jsonColumns) {
        this.dsl = dsl;
        this.unitOfWork = unitOfWork;
        this.sequencer = sequencer;
        this.jsonColumns = jsonColumns;
        this.retention = new SequencerIdRetention<>(dsl, unitOfWork, AUDIT_LOGS.ID);
    }

    @Override
    public void insert(AuditLog auditLog) {
        Map<String, Object> metadata = auditLog.getMetadata() != null ? auditLog.getMetadata() : Map.of();
        String level = TextLimits.require(AUDIT_LOGS.LEVEL, auditLog.getLevel());
        String source = TextLimits.require(AUDIT_LOGS.SOURCE, auditLog.getSource());
        String metadataJson = metadata.isEmpty() ? null : jsonColumns.write(metadata);
        unitOfWork.write(() -> {
            dsl.insertInto(AUDIT_LOGS)
                .set(new AuditLogsRecord(sequencer.next(), level, source, auditLog.getDescription(), metadataJson,
                    auditLog.getCreated()))
                .execute();
        });
    }

    @Override
    public List<AuditLog> findRecent(int limit) {
        return newestFirst(DSL.noCondition(), limit);
    }

    @Override
    public long countEvidenceUploads(String username, @Nullable Instant startDate) {
        Condition uploads = mentionsAny(EVIDENCE_WORDS)
            .or(AUDIT_LOGS.LEVEL.eq(LEVEL_INFO).and(mentionsAny(INFO_EVIDENCE_WORDS)));
        return dsl.selectCount()
            .from(AUDIT_LOGS)
            .where(ColumnLookups.matches(AUDIT_LOGS.SOURCE, username))
            .and(startDate != null ? createdSince(startDate) : DSL.noCondition())
            .and(uploads)
            .fetchSingle()
            .value1()
            .longValue();
    }

    @Override
    public long deleteOldestBatch(Instant createdBefore, int batchSize) {
        return retention.deleteOldestBatch(createdBefore, batchSize);
    }

    private List<AuditLog> newestFirst(Condition condition, int limit) {
        return dsl.selectFrom(AUDIT_LOGS)
            .where(condition)
            .orderBy(AUDIT_LOGS.ID.desc())
            .limit(limit)
            .fetch(this::toAuditLog);
    }

    private AuditLog toAuditLog(AuditLogsRecord row) {
        return AuditLog.builder()
            .id(Long.toString(row.getId()))
            .created(row.getCreatedAt())
            .level(row.getLevel())
            .source(row.getSource())
            .description(row.getDescription())
            .metadata(new HashMap<>(jsonColumns.readMap(row.getMetadataJson())))
            .build();
    }

    private static Condition createdSince(Instant start) {
        return AUDIT_LOGS.ID.ge(Sequencer.floorAt(start)).and(AUDIT_LOGS.CREATED_AT.ge(start));
    }

    private static Condition mentionsAny(List<String> lowerCaseWords) {
        return DSL.or(lowerCaseWords.stream()
            .<Condition>map(word -> DSL.lower(AUDIT_LOGS.DESCRIPTION).like("%" + word + "%"))
            .toList());
    }
}
