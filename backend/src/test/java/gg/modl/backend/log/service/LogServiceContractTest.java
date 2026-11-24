package gg.modl.backend.log.service;

import static gg.modl.backend.database.jooq.Tables.AUDIT_LOGS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import gg.modl.backend.log.dto.response.SystemLogResponse;
import gg.modl.backend.log.repository.AuditLogRepository;
import gg.modl.backend.log.repository.JooqAuditLogRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;

class LogServiceContractTest {
    private final Server server = TestServers.server("server", "admin@example.com");

    @TempDir
    Path directory;

    @SqlContractTest
    void anActionIsCommittedWhenTheCallReturnsExactlyOnceDespiteARetry(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, directory, new RetryInjection())) {
            LogService logs = service(database);

            logs.recordModerationAction(server, AuditActor.named(" "), "Ban issued for Steve");

            assertThat(database.dsl().fetchCount(AUDIT_LOGS)).isEqualTo(1);
            SystemLogResponse stored = logs.getLogs(server, 10).getFirst();
            assertThat(stored.level()).isEqualTo("moderation");
            assertThat(stored.source()).isEqualTo("system");
            assertThat(stored.description()).isEqualTo("Ban issued for Steve");
            assertThat(stored.created()).isEqualTo(SqlTestDatabase.START);
        }
    }

    @SqlContractTest
    void anActionInsideAUnitIsRecordedOnceAfterThatUnitCommits(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, directory, new RetryInjection())) {
            LogService logs = service(database);

            database.unitOfWork().write(() -> {
                logs.recordStaffAction(server, AuditActor.named("Moderator"), "Changed a role");
                assertThat(database.dsl().fetchCount(AUDIT_LOGS)).isZero();
            });

            assertThat(logs.getLogs(server, 10)).singleElement().extracting(SystemLogResponse::level).isEqualTo("info");
        }
    }

    @SqlContractTest
    void aModerationEntryIsWrittenInTheUnitOfTheAction(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, directory, new RetryInjection())) {
            LogService logs = service(database);

            database.unitOfWork().write(() -> {
                logs.recordModerationAction(server, AuditActor.named("Moderator"), "Ban issued for Player");
                assertThat(database.dsl().fetchCount(AUDIT_LOGS)).isEqualTo(1);
            });
            assertThatThrownBy(() -> database.unitOfWork().write(() -> {
                logs.recordModerationAction(server, AuditActor.named("Moderator"), "Mute issued for Player");
                throw new IllegalStateException("the action failed");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(logs.getLogs(server, 10)).singleElement().extracting(SystemLogResponse::level).isEqualTo("moderation");
        }
    }

    @SqlContractTest
    void anActionOfARolledBackUnitLeavesNoEntry(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, directory)) {
            LogService logs = service(database);

            assertThatThrownBy(() -> database.unitOfWork().write(() -> {
                logs.recordStaffAction(server, AuditActor.named("Moderator"), "Changed a role");
                throw new IllegalStateException("the action failed");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(database.dsl().fetchCount(AUDIT_LOGS)).isZero();
        }
    }

    @SqlContractTest
    void aFailedEntryDoesNotFailTheCommittedAction(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, directory)) {
            AuditLogRepository failing = mock(AuditLogRepository.class);
            doThrow(new IllegalStateException("down")).when(failing).insert(any());
            LogService logs = new LogService(failing, database.unitOfWork(), database.clock());

            assertThatCode(() -> logs.recordStaffAction(server, AuditActor.named("Moderator"), "Changed a role")).doesNotThrowAnyException();
        }
    }

    private static LogService service(SqlTestDatabase database) {
        return new LogService(new JooqAuditLogRepository(database.dsl(), database.unitOfWork(), database.sequencer(),
            database.jsonColumns()), database.unitOfWork(), database.clock());
    }
}
