package gg.modl.backend.log.repository;

import static gg.modl.backend.database.jooq.Tables.AUDIT_LOGS;
import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.audit.data.AuditLog;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;

class AuditLogRepositoryContractTest {
    private static final Instant START = SqlTestDatabase.START;

    @TempDir
    Path directory;

    @SqlContractTest
    void anEntryRoundTripsWithNestedMetadataAndADecimalId(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, directory)) {
            AuditLogRepository logs = repository(database);
            Map<String, Object> metadata = Map.of(
                "playerId", "11111111-2222-3333-4444-555555555555",
                "nested", Map.of("dotted.key", List.of("a", "漢"), "$dollar", 7),
                "rollbackDate", START);

            logs.insert(entry("moderation", "Moderator", "Rolled back a ban 😀", START, metadata));

            AuditLog stored = logs.findRecent(1).getFirst();
            long id = database.dsl().select(AUDIT_LOGS.ID).from(AUDIT_LOGS).fetchSingle(AUDIT_LOGS.ID);
            assertThat(stored.getId()).isEqualTo(Long.toString(id));
            assertThat(stored.getCreated()).isEqualTo(START);
            assertThat(stored.getLevel()).isEqualTo("moderation");
            assertThat(stored.getSource()).isEqualTo("Moderator");
            assertThat(stored.getDescription()).isEqualTo("Rolled back a ban 😀");
            assertThat(stored.getMetadata()).isEqualTo(Map.of(
                "playerId", "11111111-2222-3333-4444-555555555555",
                "nested", Map.of("dotted.key", List.of("a", "漢"), "$dollar", 7),
                "rollbackDate", "2026-01-01T00:00:00.000Z"));
        }
    }

    @SqlContractTest
    void anEntryWithoutMetadataReadsBackAsAMutableEmptyMap(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, directory)) {
            AuditLogRepository logs = repository(database);
            logs.insert(entry("info", "system", "started", START, null));

            Map<String, Object> metadata = logs.findRecent(1).getFirst().getMetadata();
            metadata.put("seen", true);

            assertThat(metadata).containsOnlyKeys("seen");
            assertThat(database.dsl().fetchCount(AUDIT_LOGS, AUDIT_LOGS.METADATA_JSON.isNull())).isEqualTo(1);
        }
    }

    @SqlContractTest
    void recencyFollowsInsertionEvenWithEqualCreationTimes(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, directory)) {
            AuditLogRepository logs = repository(database);
            List.of("first", "second", "third").forEach(text -> logs.insert(entry("info", "system", text, START, null)));

            assertThat(logs.findRecent(10)).extracting(AuditLog::getDescription).containsExactly("third", "second", "first");
            assertThat(logs.findRecent(2)).extracting(AuditLog::getDescription).containsExactly("third", "second");
        }
    }

    @SqlContractTest
    void evidenceUploadsAreCountedPerSourceAndWindow(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, directory)) {
            AuditLogRepository logs = repository(database);
            logs.insert(entry("info", "Moderator", "added EVIDENCE", START, null));
            database.clock().advance(Duration.ofDays(1));
            Instant now = database.clock().instant();
            logs.insert(entry("moderation", "Moderator", "Upload finished", now, null));
            logs.insert(entry("info", "Moderator", "added an Attachment", now, null));
            logs.insert(entry("moderation", "Moderator", "added an attachment", now, null));
            logs.insert(entry("info", "moderator", "removed a file", now, null));
            logs.insert(entry("info", "Moderator", "changed a label", now, null));

            assertThat(logs.countEvidenceUploads("Moderator", null)).isEqualTo(3);
            assertThat(logs.countEvidenceUploads("Moderator", now)).isEqualTo(2);
            assertThat(logs.countEvidenceUploads("moderator", null)).isEqualTo(1);
        }
    }

    @SqlContractTest
    void aSourceTooLongToStoreCountsNothingInsteadOfFailing(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, directory)) {
            AuditLogRepository logs = repository(database);
            int limit = TextLimits.limit(AUDIT_LOGS.SOURCE);
            String widest = "\ud83d\ude00".repeat(limit);
            logs.insert(entry("info", widest, "uploaded evidence", START, null));

            assertThat(logs.countEvidenceUploads(widest, null)).isEqualTo(1);
            assertThat(logs.countEvidenceUploads(widest + "x", null)).isZero();
            assertThat(logs.countEvidenceUploads(widest + "x", START)).isZero();
            assertThat(logs.countEvidenceUploads("x".repeat(limit * 10), null)).isZero();
        }
    }

    private static AuditLogRepository repository(SqlTestDatabase database) {
        return new JooqAuditLogRepository(database.dsl(), database.unitOfWork(), database.sequencer(), database.jsonColumns());
    }

    private static AuditLog entry(String level, String source, String description, Instant created, Map<String, Object> metadata) {
        return AuditLog.builder()
            .level(level)
            .source(source)
            .description(description)
            .created(created)
            .metadata(metadata)
            .build();
    }
}
