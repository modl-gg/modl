package gg.modl.backend.player.repository;

import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_IPS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_NOTES;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENTS;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_MODIFICATIONS;
import static gg.modl.backend.database.jooq.Tables.STAFF;
import static gg.modl.backend.player.repository.PlayerFixtures.NOW;
import static gg.modl.backend.player.repository.PlayerFixtures.uuid;
import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.database.EntityIds;
import gg.modl.backend.database.jooq.tables.records.PlayerIpsRecord;
import gg.modl.backend.database.jooq.tables.records.PunishmentsRecord;
import gg.modl.backend.database.jooq.tables.records.StaffRecord;
import gg.modl.backend.player.data.IPEntry;
import gg.modl.backend.player.data.NoteEntry;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.PlayerNoteKind;
import gg.modl.backend.player.data.UsernameEntry;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentModification;
import gg.modl.backend.player.data.punishment.PunishmentModificationType;
import gg.modl.backend.player.data.punishment.PunishmentState;
import gg.modl.backend.player.data.punishment.ResolvedCategory;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;

class PlayerImportRepositoryContractTest {
    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void anImportStoresTheCategoryAndTheDerivedActivity(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerImportRepository imports = PlayerRepositories.on(database).imports();
            Player player = player("imported",
                punishment("ban", ResolvedCategory.BAN, null),
                punishment("mute", ResolvedCategory.MUTE, Duration.ofDays(1).toMillis()),
                punishment("none", ResolvedCategory.NONE, Long.MAX_VALUE));

            assertThat(imports.importBatch(List.of(player), NOW, ImportedAddressRetention.STANDARD)).isEqualTo(new ImportBatchResult(1, 0, 0));

            assertThat(stored(database, "ban").getEnforcementCategory()).isEqualTo("BAN");
            assertThat(stored(database, "ban").getActiveUntilAt()).isEqualTo(PunishmentState.MAX);
            assertThat(stored(database, "mute").getEnforcementCategory()).isEqualTo("MUTE");
            assertThat(stored(database, "mute").getActiveUntilAt()).isEqualTo(NOW.plus(Duration.ofDays(1)));
            assertThat(stored(database, "none").getEnforcementCategory()).isEqualTo("NONE");
            assertThat(stored(database, "none").getActiveUntilAt()).isEqualTo(PunishmentState.MAX);
        }
    }

    @SqlContractTest
    void importingTheSameFileTwiceChangesNothing(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerImportRepository imports = PlayerRepositories.on(database).imports();
            Player player = player("imported", punishment("p-1", ResolvedCategory.BAN, null),
                punishment("x".repeat(90), ResolvedCategory.BAN, null));
            player.getNotes().add(new NoteEntry("note-1", "watch", NOW, "Moderator", null, PlayerNoteKind.WARNING));
            player.getIpAddresses().add(address(NOW.minusSeconds(100), NOW, 7));

            imports.importBatch(List.of(player), NOW, ImportedAddressRetention.STANDARD);
            ImportBatchResult again = imports.importBatch(List.of(player), NOW.plusSeconds(60), ImportedAddressRetention.STANDARD);

            assertThat(again).isEqualTo(new ImportBatchResult(0, 1, 0));
            assertThat(database.dsl().fetchCount(PLAYERS)).isEqualTo(1);
            assertThat(database.dsl().fetchCount(PUNISHMENTS)).isEqualTo(2);
            assertThat(database.dsl().fetchCount(PLAYER_NOTES)).isEqualTo(1);
            assertThat(database.dsl().fetchSingle(PLAYER_NOTES).getKind()).isEqualTo(PlayerNoteKind.WARNING.name());
            assertThat(database.dsl().fetchSingle(PLAYER_IPS).getLoginCount()).isEqualTo(7L);
        }
    }

    @SqlContractTest
    void anIdTakenByAnotherPlayerIsReplacedAndRemembered(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerImportRepository imports = PlayerRepositories.on(database).imports();
            imports.importBatch(List.of(player("owner", punishment("shared", ResolvedCategory.BAN, null))), NOW, ImportedAddressRetention.STANDARD);
            Player second = player("second", punishment("shared", ResolvedCategory.MUTE, null));

            imports.importBatch(List.of(second), NOW, ImportedAddressRetention.STANDARD);
            imports.importBatch(List.of(second), NOW, ImportedAddressRetention.STANDARD);

            assertThat(stored(database, "shared").getEnforcementCategory()).isEqualTo("BAN");
            PunishmentsRecord replaced = database.dsl().fetchSingle(PUNISHMENTS, PUNISHMENTS.ID.ne("shared"));
            assertThat(replaced.getEnforcementCategory()).isEqualTo("MUTE");
            assertThat(replaced.getId()).hasSize(8);
            assertThat(database.jsonColumns().readMap(replaced.getExtraJson())).containsEntry("importedId", "shared");
        }
    }

    @SqlContractTest
    void blankAndOverlongIdsAreGenerated(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerImportRepository imports = PlayerRepositories.on(database).imports();
            String overlong = "y".repeat(65);

            imports.importBatch(List.of(player("imported", punishment(" ", ResolvedCategory.BAN, null),
                punishment(overlong, ResolvedCategory.BAN, null))), NOW, ImportedAddressRetention.STANDARD);

            List<PunishmentsRecord> stored = database.dsl().fetch(PUNISHMENTS);
            assertThat(stored).hasSize(2).allSatisfy(row -> assertThat(row.getId()).hasSize(8));
            assertThat(stored).anySatisfy(row ->
                assertThat(database.jsonColumns().readMap(row.getExtraJson())).containsEntry("importedId", overlong));
        }
    }

    @SqlContractTest
    void onlyStaffIdsStayIssuerIds(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            StaffRecord staff = new StaffRecord();
            staff.setId("staff0000000001");
            staff.setEmail("mod@example.com");
            staff.setUsername("Mod");
            staff.setCreatedAt(NOW);
            staff.setUpdatedAt(NOW);
            database.unitOfWork().write(() -> database.dsl().insertInto(STAFF).set(staff).execute());
            Punishment byStaff = punishment("by-staff", ResolvedCategory.BAN, null);
            byStaff.setIssuerId(staff.getId());
            Punishment byStranger = punishment("by-stranger", ResolvedCategory.BAN, null);
            byStranger.setIssuerName(null);
            byStranger.setIssuerId("OldModerator");
            byStranger.getModifications().add(new PunishmentModification("m1", PunishmentModificationType.MANUAL_PARDON.name(),
                NOW, "Named", "unknown-id", "", null, null, null));

            PlayerRepositories.on(database).imports().importBatch(List.of(player("imported", byStaff, byStranger)), NOW, ImportedAddressRetention.STANDARD);

            assertThat(stored(database, "by-staff").getIssuerId()).isEqualTo(staff.getId());
            PunishmentsRecord named = stored(database, "by-stranger");
            assertThat(named.getIssuerId()).isNull();
            assertThat(named.getIssuerName()).isEqualTo("OldModerator");
            assertThat(named.getActiveUntilAt()).isEqualTo(PunishmentState.INACTIVE);
            assertThat(database.dsl().fetchSingle(PUNISHMENT_MODIFICATIONS).getIssuerId()).isNull();
            assertThat(database.dsl().fetchSingle(PUNISHMENT_MODIFICATIONS).getIssuerName()).isEqualTo("Named");
        }
    }

    @SqlContractTest
    void mergingWidensSightingsAndKeepsTheCurrentName(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepositories repositories = PlayerRepositories.on(database);
            PlayerFixtures.login(repositories.players(), "Current", "203.0.113.7", PlayerFixtures.proxy(), NOW);
            Player imported = player("Current");
            imported.setUsernames(new ArrayList<>(List.of(new UsernameEntry("Former", NOW.minusSeconds(500)))));
            imported.getIpAddresses().add(address(NOW.minusSeconds(1000), NOW.minusSeconds(10), 5));

            repositories.imports().importBatch(List.of(imported), NOW, ImportedAddressRetention.STANDARD);

            PlayerIpsRecord merged = database.dsl().fetchSingle(PLAYER_IPS);
            assertThat(merged.getFirstSeenAt()).isEqualTo(NOW.minusSeconds(1000));
            assertThat(merged.getLastSeenAt()).isEqualTo(NOW);
            assertThat(merged.getLoginCount()).isEqualTo(5L);
            assertThat(merged.getIsProxy()).isTrue();
            assertThat(repositories.players().findByMinecraftUuid(uuid("Current").toString(), PlayerParts.USERNAMES).orElseThrow()
                .getUsernames()).extracting(UsernameEntry::username).containsExactly("Former", "Current");
        }
    }

    @SqlContractTest
    void repeatedRecordsInOneBatchMergeAndSurviveARetry(DialectTarget target) {
        RetryInjection retry = new RetryInjection();
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, retry)) {
            Player first = player("twice", punishment("p-1", ResolvedCategory.BAN, null));
            Player second = player("twice", punishment("p-1", ResolvedCategory.BAN, null),
                punishment("p-2", ResolvedCategory.MUTE, null));

            ImportBatchResult result = PlayerRepositories.on(database).imports().importBatch(List.of(first, second), NOW, ImportedAddressRetention.STANDARD);

            assertThat(result).isEqualTo(new ImportBatchResult(1, 1, 0));
            assertThat(retry.injected()).isPositive();
            assertThat(database.dsl().fetchCount(PUNISHMENTS)).isEqualTo(2);
        }
    }

    @SqlContractTest
    void aPlayerWithoutAUuidIsSkipped(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            Player broken = Player.builder().build();

            assertThat(PlayerRepositories.on(database).imports().importBatch(List.of(broken), NOW, ImportedAddressRetention.STANDARD))
                .isEqualTo(new ImportBatchResult(0, 0, 1));
            assertThat(database.dsl().fetchCount(PLAYERS)).isZero();
        }
    }

    private static Player player(String name, Punishment... punishments) {
        return Player.builder()
            .minecraftUuid(uuid(name))
            .usernames(new ArrayList<>(List.of(new UsernameEntry(name, NOW))))
            .punishments(new ArrayList<>(List.of(punishments)))
            .build();
    }

    private static Punishment punishment(String id, ResolvedCategory category, Long duration) {
        Punishment punishment = PlayerFixtures.punishment(2, category, NOW, NOW, duration);
        punishment.setId(id != null ? id : EntityIds.next());
        return punishment;
    }

    @SqlContractTest
    void keptHistoryIsRetainedOnNewAndMergedAddressesAndAStandardImportLeavesItAlone(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerImportRepository imports = PlayerRepositories.on(database).imports();
            Player player = player("imported");
            player.getIpAddresses().add(address(NOW.minusSeconds(100), NOW, 2));

            imports.importBatch(List.of(player), NOW, ImportedAddressRetention.STANDARD);
            assertThat(database.dsl().fetchSingle(PLAYER_IPS).getRetainedUntilAt()).isNull();

            imports.importBatch(List.of(player), NOW, ImportedAddressRetention.KEPT);
            assertThat(database.dsl().fetchSingle(PLAYER_IPS).getRetainedUntilAt())
                .isEqualTo(ImportedAddressRetention.KEPT.retainedUntil());

            imports.importBatch(List.of(player), NOW, ImportedAddressRetention.STANDARD);
            assertThat(database.dsl().fetchSingle(PLAYER_IPS).getRetainedUntilAt())
                .isEqualTo(ImportedAddressRetention.KEPT.retainedUntil());
        }
    }

    private static IPEntry address(Instant first, Instant last, long count) {
        return IPEntry.builder().ipAddress("203.0.113.7").firstLogin(first).lastLogin(last).loginCount(count).build();
    }

    private static PunishmentsRecord stored(SqlTestDatabase database, String id) {
        return database.dsl().fetchSingle(PUNISHMENTS, PUNISHMENTS.ID.eq(id));
    }
}
