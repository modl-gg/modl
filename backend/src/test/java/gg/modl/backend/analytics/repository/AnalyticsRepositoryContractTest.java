package gg.modl.backend.analytics.repository;

import static gg.modl.backend.analytics.repository.ReadModelRows.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.database.jooq.tables.records.IpLookupsRecord;
import gg.modl.backend.iplookup.model.IpLookupProvider;
import gg.modl.backend.iplookup.model.VpnStatus;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jooq.TableRecord;
import org.junit.jupiter.api.io.TempDir;

class AnalyticsRepositoryContractTest {
    private static final Instant THIRTY_DAYS_AGO = NOW.minus(Duration.ofDays(30));
    private static final Instant SIXTY_DAYS_AGO = NOW.minus(Duration.ofDays(60));
    private static final Instant YESTERDAY = NOW.minus(Duration.ofDays(1));

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void ticketFacetsIgnoreUnfinishedTicketsAndGroupDaysInUtc(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            ReadModelRows.insert(database,
                ReadModelRows.ticket("T-1", "bug", "closed", YESTERDAY, YESTERDAY.plusSeconds(7_200)),
                ReadModelRows.ticket("T-2", "bug", "closed", YESTERDAY.plusSeconds(86_399), YESTERDAY.plusSeconds(86_399 + 3_600)),
                ReadModelRows.ticket("T-3", "support", "open", NOW, NOW),
                ReadModelRows.ticket("T-4", "support", "unfinished", NOW, NOW),
                ReadModelRows.ticket("T-5", "support", "open", SIXTY_DAYS_AGO, NOW));
            JooqAnalyticsRepository repository = repository(database);

            assertThat(repository.countTicketsByStatus(THIRTY_DAYS_AGO)).containsExactly(Map.entry("closed", 2L), Map.entry("open", 1L));
            assertThat(repository.countTicketsByCategory(THIRTY_DAYS_AGO)).containsExactly(Map.entry("bug", 2L), Map.entry("support", 1L));
            assertThat(repository.countTicketsByDay(THIRTY_DAYS_AGO))
                .containsExactly(new TimeBucketCount(YESTERDAY, 2), new TimeBucketCount(NOW, 1));
            assertThat(repository.averageResolutionByCategory(THIRTY_DAYS_AGO))
                .containsExactly(new CategoryResolution("bug", 5_400_000.0, 2));
            assertThat(repository.countTicketsByStatus(Instant.EPOCH)).containsEntry("open", 2L);
        }
    }

    @SqlContractTest
    void punishmentBreakdownCountsEveryOrdinalAndDay(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            ReadModelRows.insert(database,
                ReadModelRows.player("p1", null),
                ReadModelRows.punishment("a", "p1", 18, "Alice", "staff-1", YESTERDAY, NOW),
                ReadModelRows.punishment("b", "p1", 18, null, "staff-1", YESTERDAY.plusSeconds(5), NOW),
                ReadModelRows.punishment("c", "p1", 2, "Bob", null, NOW, NOW),
                ReadModelRows.punishment("d", "p1", 2, null, null, NOW, NOW),
                ReadModelRows.punishment("e", "p1", 2, null, null, SIXTY_DAYS_AGO, NOW));

            PunishmentBreakdown breakdown = repository(database).loadPunishmentBreakdown(THIRTY_DAYS_AGO);

            assertThat(breakdown.byTypeOrdinal()).containsExactly(Map.entry(2, 2L), Map.entry(18, 2L));
            assertThat(breakdown.daily()).containsExactly(new TimeBucketCount(YESTERDAY, 2), new TimeBucketCount(NOW, 2));
        }
    }

    @SqlContractTest
    void auditFacetsStartAtTheGivenInstantAndGroupByHour(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            ReadModelRows.insert(database,
                ReadModelRows.log(1, "info", "alice", "one", YESTERDAY),
                ReadModelRows.log(2, "info", "alice", "two", YESTERDAY.plusSeconds(3_599)),
                ReadModelRows.log(3, "moderation", "alice", "three", YESTERDAY.plusSeconds(3_600)),
                ReadModelRows.log(4, "info", "alice", "before the window", YESTERDAY.minusMillis(1)));
            JooqAnalyticsRepository repository = repository(database);

            assertThat(repository.countAuditLogsByLevel(YESTERDAY)).containsExactly(Map.entry("info", 2L), Map.entry("moderation", 1L));
            assertThat(repository.countAuditLogsByHour(YESTERDAY))
                .containsExactly(new TimeBucketCount(YESTERDAY, 2), new TimeBucketCount(YESTERDAY.plusSeconds(3_600), 1));
        }
    }

    @SqlContractTest
    void playerActivityCountsLoginsInTheWindowAndFlags(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            ReadModelRows.insert(database,
                ReadModelRows.player("p1", YESTERDAY),
                ReadModelRows.player("p2", SIXTY_DAYS_AGO),
                ReadModelRows.ip("p1", "1.1.1.1", "DE", YESTERDAY, NOW, 5, true, false),
                ReadModelRows.ip("p2", "2.2.2.2", "US", SIXTY_DAYS_AGO, NOW, 40, false, true),
                ReadModelRows.ip("p2", "3.3.3.3", "FR", SIXTY_DAYS_AGO, SIXTY_DAYS_AGO, 9, true, true),
                ReadModelRows.ip("p2", "4.4.4.4", "", YESTERDAY, NOW, 3, false, false),
                ReadModelRows.ip("p2", "5.5.5.5", null, YESTERDAY, NOW, 3, false, false),
                lookup("2.2.2.2", VpnStatus.YES),
                lookup("4.4.4.4", VpnStatus.NO));
            JooqAnalyticsRepository repository = repository(database);

            PlayerActivity recent = repository.loadPlayerActivity(THIRTY_DAYS_AGO);
            assertThat(recent.newPlayers()).containsExactly(new TimeBucketCount(YESTERDAY, 1));
            assertThat(recent.byCountry()).containsExactly(Map.entry("DE", 5L), Map.entry("US", 1L));
            assertThat(recent.vpn()).isEqualTo(new PlayerActivity.VpnCounts(2, 1, 1));
            assertThat(recent.hostingCount()).isEqualTo(1);

            PlayerActivity allTime = repository.loadPlayerActivity(Instant.EPOCH);
            assertThat(allTime.byCountry()).containsExactly(Map.entry("US", 40L), Map.entry("FR", 9L), Map.entry("DE", 5L));
            assertThat(allTime.vpn()).isEqualTo(new PlayerActivity.VpnCounts(3, 1, 1));
        }
    }

    @SqlContractTest
    void anAddressWithoutAnyLookupIsCountedAsUnknownNeverAsClean(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            ReadModelRows.insert(database,
                ReadModelRows.player("p1", YESTERDAY),
                ReadModelRows.ip("p1", "6.6.6.6", "NL", YESTERDAY, NOW, 1, false, false),
                ReadModelRows.ip("p1", "7.7.7.7", "NL", YESTERDAY, NOW, 1, false, false),
                lookup("7.7.7.7", VpnStatus.UNKNOWN));

            assertThat(repository(database).loadPlayerActivity(THIRTY_DAYS_AGO).vpn())
                .isEqualTo(new PlayerActivity.VpnCounts(0, 0, 2));
        }
    }

    @SqlContractTest
    void countriesAreCappedWithAStableTieBreak(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            List<TableRecord<?>> rows = new ArrayList<>(List.of(ReadModelRows.player("p1", null)));
            for (int index = 0; index <= JooqAnalyticsRepository.TOP_COUNTRIES; index++) {
                rows.add(ReadModelRows.ip("p1", "10.0.0." + index, "C" + (index + 10), YESTERDAY, NOW, 1, false, false));
            }
            ReadModelRows.insertAll(database, rows);

            assertThat(repository(database).loadPlayerActivity(THIRTY_DAYS_AGO).byCountry().keySet())
                .hasSize(JooqAnalyticsRepository.TOP_COUNTRIES)
                .startsWith("C10", "C11")
                .doesNotContain("C30");
        }
    }

    @SqlContractTest
    void emptyTablesAnswerWithZerosInsteadOfFailing(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqAnalyticsRepository repository = repository(database);

            assertThat(repository.averageResolutionByCategory(THIRTY_DAYS_AGO)).isEmpty();
            assertThat(repository.loadPlayerActivity(THIRTY_DAYS_AGO))
                .isEqualTo(new PlayerActivity(List.of(), Map.of(), new PlayerActivity.VpnCounts(0, 0, 0), 0));
        }
    }

    private static IpLookupsRecord lookup(String ip, VpnStatus vpn) {
        return new IpLookupsRecord(ip, IpLookupProvider.VPNAPI.id(), null, null, null, vpn.name(), false, NOW);
    }

    @SqlContractTest
    void readsCannotBypassTheReportLane(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqAnalyticsRepository repository = repository(database);

            assertThatThrownBy(() -> database.unitOfWork().read(() -> repository.countTicketsByStatus(THIRTY_DAYS_AGO)))
                .isInstanceOf(IllegalStateException.class);
        }
    }

    private static JooqAnalyticsRepository repository(SqlTestDatabase database) {
        return new JooqAnalyticsRepository(database.dsl(), database.unitOfWork());
    }
}
