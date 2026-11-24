package gg.modl.backend.audit.service;

import static gg.modl.backend.analytics.repository.ReadModelRows.NOW;
import static gg.modl.backend.database.jooq.Tables.AUDIT_LOGS;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENTS;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_MODIFICATIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.analytics.repository.ReadModelRows;
import gg.modl.backend.audit.dto.response.AuditPunishmentResponse;
import gg.modl.backend.audit.dto.response.PunishmentAuditPage;
import gg.modl.backend.database.jooq.tables.records.PunishmentsRecord;
import gg.modl.backend.log.repository.JooqAuditLogRepository;
import gg.modl.backend.player.PunishmentStore;
import gg.modl.backend.player.data.punishment.PunishmentPhase;
import gg.modl.backend.player.data.punishment.PunishmentState;
import gg.modl.backend.player.repository.JooqPunishmentRepository;
import gg.modl.backend.player.service.IssuerNameResolver;
import gg.modl.backend.player.service.PunishmentIssuer;
import gg.modl.backend.player.service.PunishmentLifecycleService;
import gg.modl.backend.player.service.PunishmentMutationService;
import gg.modl.backend.player.service.PunishmentServices;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.service.OffenderThresholdSettingsService;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.backend.staff.service.StaffService;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.TableRecord;
import org.junit.jupiter.api.io.TempDir;

class AuditComposerContractTest {
    private static final String UUID_PATTERN = "[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}";
    private static final Instant ISSUED = NOW.minus(Duration.ofHours(1));
    private static final PunishmentIssuer OWNER = PunishmentIssuer.staff("owner@example.com", null);
    private static final Instant ACTIVE_UNTIL = Instant.parse("9999-12-31T23:59:59.999Z");

    private final Server server = TestServers.server("Demo", "admin@example.com");
    private final PunishmentTypeService punishmentTypeService = mock(PunishmentTypeService.class);
    private final StaffService staffService = mock(StaffService.class);

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void theListAndRollbackMetadataCarryTheMinecraftUuid(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            when(punishmentTypeService.getPunishmentTypeName(any(), anyInt())).thenReturn("Cheating (deleted)");
            ReadModelRows.insert(database,
                ReadModelRows.player("p1", null),
                inForce(ReadModelRows.punishment("ban-1", "p1", 18, "Alice", null, ISSUED, ACTIVE_UNTIL)));

            AuditPunishmentResponse listed = queries(database).getPunishmentsList(server, newestFirst("active"), 10, null).punishments().getFirst();
            assertThat(listed.phase()).isEqualTo(PunishmentPhase.ACTIVE);
            assertThat(listed.playerId()).matches(UUID_PATTERN).isEqualTo(ReadModelRows.uuidOf("p1"));
            assertThat(listed.type()).isEqualTo("Cheating (deleted)");

            assertThat(commands(database).rollbackPunishment(server, "ban-1", "mistake", OWNER)).isTrue();

            assertThat(database.dsl().fetchCount(PUNISHMENT_MODIFICATIONS)).isEqualTo(1);
            assertThat(database.dsl().select(PUNISHMENTS.MODIFIED_AT).from(PUNISHMENTS).fetchSingle().value1()).isEqualTo(NOW);
            assertThat(database.dsl().select(PUNISHMENTS.ACTIVE_UNTIL_AT).from(PUNISHMENTS).fetchSingle().value1())
                .isEqualTo(PunishmentState.INACTIVE);
            String metadata = database.dsl().select(AUDIT_LOGS.METADATA_JSON).from(AUDIT_LOGS).fetchSingle().value1();
            assertThat(UUID.fromString(String.valueOf(database.jsonColumns().readMap(metadata).get("playerId"))))
                .hasToString(ReadModelRows.uuidOf("p1"));
        }
    }

    @SqlContractTest
    void cursorPagesVisitEveryPunishmentOnceAcrossTiesAndBothStates(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            when(punishmentTypeService.getPunishmentTypeName(any(), anyInt())).thenReturn("Cheating");
            ReadModelRows.insert(database,
                ReadModelRows.player("p1", null),
                inForce(ReadModelRows.punishment("a", "p1", 18, null, null, ISSUED, ACTIVE_UNTIL)),
                ReadModelRows.punishment("b", "p1", 18, null, null, ISSUED, Instant.EPOCH),
                inForce(ReadModelRows.punishment("c", "p1", 18, null, null, ISSUED, ACTIVE_UNTIL)),
                ReadModelRows.punishment("d", "p1", 18, null, null, ISSUED, Instant.EPOCH),
                inForce(ReadModelRows.punishment("e", "p1", 18, null, null, ISSUED.minusSeconds(1), ACTIVE_UNTIL)),
                ReadModelRows.punishment("f", "p1", 18, null, null, ISSUED, ACTIVE_UNTIL),
                ReadModelRows.punishment("k", "p1", 0, null, null, ISSUED, Instant.EPOCH));

            assertThat(idsOfEveryPage(database, "all")).containsExactly("f", "d", "c", "b", "a", "e");
            assertThat(idsOfEveryPage(database, "active")).containsExactly("c", "a", "e");
            assertThat(idsOfEveryPage(database, "unstarted")).containsExactly("f");
            assertThat(idsOfEveryPage(database, "ended")).containsExactly("d", "b");
        }
    }

    @SqlContractTest
    void bulkRollbackCrossesPagesAndWritesEachPunishmentOnce(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            when(punishmentTypeService.getPunishmentTypeName(any(), anyInt())).thenReturn("Cheating");
            when(staffService.getStaffByUsername(server, "Alice")).thenReturn(Optional.empty());
            int total = AuditService.BULK_PAGE_SIZE + 1;
            List<TableRecord<?>> rows = new ArrayList<>(List.of(ReadModelRows.player("p1", null)));
            for (int index = 0; index < total; index++) {
                rows.add(ReadModelRows.punishment("ban-" + index, "p1", 18, "Alice", null, ISSUED, ACTIVE_UNTIL));
            }
            ReadModelRows.insertAll(database, rows);
            AuditService commands = commands(database);

            assertThat(commands.rollbackAllPunishmentsByStaff(server, "Alice", "compromised", OWNER)).isEqualTo(total);
            assertThat(database.dsl().fetchCount(PUNISHMENT_MODIFICATIONS)).isEqualTo(total);
            assertThat(database.dsl().fetchCount(AUDIT_LOGS)).isEqualTo(total);
            assertThat(database.dsl().fetchCount(PUNISHMENTS, PUNISHMENTS.ACTIVE_UNTIL_AT.ne(PunishmentState.INACTIVE))).isZero();
            assertThat(commands.rollbackAllPunishmentsByStaff(server, "Alice", "compromised", OWNER)).isZero();
        }
    }

    private List<String> idsOfEveryPage(SqlTestDatabase database, String status) {
        List<String> ids = new ArrayList<>();
        String cursor = null;
        do {
            PunishmentAuditPage page = queries(database).getPunishmentsList(server, newestFirst(status), 2, cursor);
            page.punishments().forEach(punishment -> ids.add(punishment.id()));
            cursor = page.nextCursor();
        } while (cursor != null);
        return ids;
    }

    private static PunishmentsRecord inForce(PunishmentsRecord punishment) {
        punishment.setStartedAt(ISSUED);
        return punishment;
    }

    private static PunishmentListRequest newestFirst(String status) {
        return new PunishmentListRequest(status, "issued", "desc", null, null, "all");
    }

    private PunishmentAuditQueryService queries(SqlTestDatabase database) {
        return new PunishmentAuditQueryService(punishments(database), punishmentTypeService, mock(IssuerNameResolver.class), staffService, database.clock());
    }

    private AuditService commands(SqlTestDatabase database) {
        return new AuditService(database.unitOfWork(), punishments(database), auditLogs(database), punishmentTypeService,
            staffService, lifecycle(database), mock(PunishmentMutationService.class),
            new IssuerNameResolver(mock(StaffRepository.class)), database.clock());
    }

    private PunishmentLifecycleService lifecycle(SqlTestDatabase database) {
        return PunishmentServices.on(database, PunishmentStore.on(database), punishmentTypeService,
            mock(OffenderThresholdSettingsService.class)).lifecycle();
    }

    private static JooqAuditLogRepository auditLogs(SqlTestDatabase database) {
        return new JooqAuditLogRepository(database.dsl(), database.unitOfWork(), database.sequencer(), database.jsonColumns());
    }

    private static JooqPunishmentRepository punishments(SqlTestDatabase database) {
        return new JooqPunishmentRepository(database.dsl(), database.unitOfWork(), database.insertIfAbsent(),
            database.childRows(), database.jsonColumns());
    }
}
