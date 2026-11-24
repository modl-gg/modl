package gg.modl.backend.player.repository;

import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_IPS;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENTS;
import static gg.modl.backend.player.repository.PlayerFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.player.data.punishment.PunishmentIssuerKind;
import gg.modl.backend.player.data.IPEntry;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.UsernameEntry;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.ResolvedCategory;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.io.TempDir;

class PlayerSnapshotImportContractTest {
    private static final UUID FIRST = UUID.fromString("0b7e8a3c-6c55-4b3a-9d8a-111111111111");

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void aSnapshotKeepsItsIdsAndCreationTime(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerImportRepository imports = PlayerRepositories.on(database).imports();
            SnapshotImportResult result = imports.importSnapshots(
                List.of(new PlayerSnapshot(player("archived-id", "kept-punishment"), NOW.minus(Duration.ofDays(400)))), id -> "x-" + id,
                ImportedAddressRetention.STANDARD);

            assertThat(result.storedIdByUuid()).containsEntry(FIRST.toString(), "archived-id");
            assertThat(database.dsl().fetchSingle(PLAYERS).getCreatedAt()).isEqualTo(NOW.minus(Duration.ofDays(400)));
            assertThat(database.dsl().fetchSingle(PUNISHMENTS).getId()).isEqualTo("kept-punishment");
        }
    }

    @SqlContractTest
    void aLivePlayerKeepsItsIdAndAClashingPunishmentIdIsRenamed(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerImportRepository imports = PlayerRepositories.on(database).imports();
            imports.importSnapshots(List.of(new PlayerSnapshot(player("live-id", "taken"), NOW)), id -> "x-" + id, ImportedAddressRetention.STANDARD);

            SnapshotImportResult again = imports.importSnapshots(
                List.of(new PlayerSnapshot(player("archived-id", "taken"), NOW.minus(Duration.ofDays(1)))), id -> "renamed-" + id,
                ImportedAddressRetention.STANDARD);

            assertThat(again.storedIdByUuid()).containsEntry(FIRST.toString(), "live-id");
            assertThat(again.merged()).isEqualTo(1);
            assertThat(again.renamedPunishmentIds()).containsEntry("taken", "renamed-taken");
            assertThat(database.dsl().fetchCount(PUNISHMENTS)).isEqualTo(2);
            assertThat(database.dsl().fetchSingle(PLAYERS).getCreatedAt()).isEqualTo(NOW.minus(Duration.ofDays(1)));
        }
    }

    @SqlContractTest
    void aHostedImportRetainsTheAddressesItWritesOnlyWhenHistoryIsKept(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerImportRepository imports = PlayerRepositories.on(database).imports();
            Player kept = player("kept-id", "p-1");
            kept.getIpAddresses().add(IPEntry.builder().ipAddress("203.0.113.9").firstLogin(NOW.minus(Duration.ofDays(500)))
                .lastLogin(NOW.minus(Duration.ofDays(500))).loginCount(1L).build());

            imports.importSnapshots(List.of(new PlayerSnapshot(kept, NOW)), id -> "x-" + id, ImportedAddressRetention.KEPT);

            assertThat(database.dsl().fetchSingle(PLAYER_IPS).getRetainedUntilAt())
                .isEqualTo(ImportedAddressRetention.KEPT.retainedUntil());
        }
    }

    private static Player player(String id, String punishmentId) {
        Map<String, Object> data = new HashMap<>();
        data.put("enforcementCategory", ResolvedCategory.BAN.name());
        Punishment punishment = new Punishment(punishmentId, 2, "Moderator", null, PunishmentIssuerKind.STAFF, NOW, NOW, new ArrayList<>(), new ArrayList<>(),
            new ArrayList<>(), new ArrayList<>(), data);
        return Player.builder()
            .id(id)
            .minecraftUuid(FIRST)
            .usernames(new ArrayList<>(List.of(new UsernameEntry("Steve", NOW))))
            .ipAddresses(new ArrayList<>())
            .punishments(new ArrayList<>(List.of(punishment)))
            .build();
    }
}
