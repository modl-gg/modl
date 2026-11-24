package gg.modl.backend.player.repository;

import static gg.modl.backend.database.jooq.Tables.PLAYER_IPS;
import static gg.modl.backend.player.repository.PlayerFixtures.NOW;
import static gg.modl.backend.player.repository.PlayerFixtures.login;
import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.io.TempDir;

class RetainedIpHistoryRepositoryContractTest {
    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void nothingIsRetainedUntilAnImportKeepsHistory(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            login(PlayerRepositories.on(database).players(), "Steve", "198.51.100.1", NOW);
            RetainedIpHistoryRepository retained = new JooqRetainedIpHistoryRepository(database.dsl(), database.unitOfWork());

            assertThat(retained.latestRetainedSighting()).isEmpty();
            assertThat(retained.anyRetained()).isFalse();
            assertThat(retained.releaseBatch(10)).isZero();
        }
    }

    @SqlContractTest
    void theNewestRetainedSightingIsReportedAndReleaseIsBoundedPerCall(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepository players = PlayerRepositories.on(database).players();
            Instant older = NOW.minus(Duration.ofDays(900));
            Instant newer = NOW.minus(Duration.ofDays(600));
            String first = login(players, "older", "198.51.100.1", older);
            String second = login(players, "newer", "198.51.100.2", newer);
            login(players, "live", "198.51.100.3", NOW);
            database.unitOfWork().write(() -> database.dsl().update(PLAYER_IPS)
                .set(PLAYER_IPS.RETAINED_UNTIL_AT, ImportedAddressRetention.KEPT.retainedUntil())
                .where(PLAYER_IPS.PLAYER_ID.in(first, second))
                .execute());
            RetainedIpHistoryRepository retained = new JooqRetainedIpHistoryRepository(database.dsl(), database.unitOfWork());

            assertThat(retained.latestRetainedSighting()).contains(newer);
            assertThat(retained.releaseBatch(1)).isEqualTo(1);
            assertThat(retained.anyRetained()).isTrue();
            assertThat(database.dsl().fetchCount(PLAYER_IPS, PLAYER_IPS.RETAINED_UNTIL_AT.isNotNull())).isEqualTo(1);
            assertThat(retained.releaseBatch(10)).isEqualTo(1);
            assertThat(retained.anyRetained()).isFalse();
            assertThat(retained.latestRetainedSighting()).isEmpty();
            assertThat(database.dsl().fetchCount(PLAYER_IPS)).isEqualTo(3);
        }
    }
}
