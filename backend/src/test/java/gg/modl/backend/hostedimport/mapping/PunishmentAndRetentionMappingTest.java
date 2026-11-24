package gg.modl.backend.hostedimport.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.hostedimport.HostedArchiveFixture;
import gg.modl.backend.hostedimport.ImportTestContexts;
import gg.modl.backend.hostedimport.archive.ArchiveDocument;
import gg.modl.backend.hostedimport.archive.ExtendedJsonDecoder;
import gg.modl.backend.hostedimport.mapping.PlayerMapper.MappedPlayer;
import gg.modl.backend.hostedimport.model.IssueCode;
import gg.modl.backend.hostedimport.model.RetentionChoice;
import gg.modl.backend.hostedimport.model.RetentionPlan;
import gg.modl.backend.player.data.IPEntry;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentNote;
import gg.modl.backend.player.data.punishment.PunishmentModificationType;
import gg.modl.backend.player.data.punishment.PunishmentState;
import gg.modl.backend.player.data.punishment.ResolvedCategory;
import gg.modl.backend.settings.data.DataRetentionSettings;
import gg.modl.backend.settings.data.DefaultPunishmentTypes;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.data.ReplayRetentionSettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PunishmentAndRetentionMappingTest {
    private static final String UUID = "0b7e8a3c-6c55-4b3a-9d8a-111111111111";

    @TempDir
    Path directory;

    @Test
    void restrictionsWithoutAKnownIdentityAreNotEnforcedAndLoseTheirOptions() throws IOException {
        List<PunishmentType> types = new ArrayList<>(DefaultPunishmentTypes.getAll());
        types.add(PunishmentType.builder().id(90).ordinal(90).name("Bad Name").category("Administrative")
            .permanentUntilUsernameChange(true).build());
        MappedPlayer player = map(types, "{\"id\":\"r1\",\"typeOrdinal\":90,\"issued\":{\"$date\":\"2026-01-01T00:00:00Z\"},"
            + "\"data\":{\"enforcementCategory\":\"BAN\",\"blockedName\":\"Unknown\",\"altBlocking\":true,\"wipeAfterExpiry\":true}}",
            "{\"id\":\"r2\",\"typeOrdinal\":90,\"issued\":{\"$date\":\"2026-01-01T00:00:00Z\"},"
            + "\"data\":{\"enforcementCategory\":\"BAN\",\"blockedName\":\"Griefer\"}}",
            "{\"id\":\"r3\",\"typeOrdinal\":2,\"issued\":{\"$date\":\"2026-01-01T00:00:00Z\"},\"data\":{\"enforcementCategory\":\"NONE\"}}");

        Punishment unknown = punishment(player, "r1");
        assertThat(unknown.enforcementCategory()).isEqualTo(ResolvedCategory.NONE);
        assertThat(unknown.data().altBlocking()).isFalse();
        assertThat(unknown.data().wipeAfterExpiry()).isFalse();
        assertThat(unknown.data().blockedName()).isEqualTo("Unknown");
        assertThat(punishment(player, "r2").enforcementCategory()).isEqualTo(ResolvedCategory.BAN);
        assertThat(punishment(player, "r3").enforcementCategory()).isEqualTo(ResolvedCategory.NONE);
    }

    @Test
    void aPunishmentWithoutDataIsImportedAsPardonedAndARollbackEndsEnforcement() throws IOException {
        MappedPlayer player = map(DefaultPunishmentTypes.getAll(),
            "{\"id\":\"n1\",\"typeOrdinal\":2,\"issued\":{\"$date\":\"2026-01-01T00:00:00Z\"}}",
            "{\"id\":\"rb\",\"typeOrdinal\":2,\"issued\":{\"$date\":\"2026-01-01T00:00:00Z\"},\"data\":{\"enforcementCategory\":\"BAN\"},"
                + "\"modifications\":[{\"id\":\"m\",\"type\":\"ROLLBACK\",\"date\":{\"$date\":\"2026-01-02T00:00:00Z\"}}]}",
            "{\"id\":\"deleted\",\"typeOrdinal\":44,\"issued\":{\"$date\":\"2026-01-01T00:00:00Z\"},\"data\":{\"enforcementCategory\":\"MUTE\"}}");

        Punishment noData = punishment(player, "n1");
        assertThat(noData.getModifications()).extracting(modification -> modification.type())
            .containsExactly(PunishmentModificationType.SYSTEM_PARDON.name());
        assertThat(PunishmentState.activeUntil(noData)).isEqualTo(PunishmentState.INACTIVE);
        assertThat(PunishmentState.activeUntil(punishment(player, "rb"))).isEqualTo(PunishmentState.INACTIVE);
        assertThat(punishment(player, "deleted").enforcementCategory()).isEqualTo(ResolvedCategory.MUTE);
    }

    @Test
    void legacyRemoveAndRevokeArriveAsPardonsThatEndEnforcement() throws IOException {
        MappedPlayer player = map(DefaultPunishmentTypes.getAll(),
            endedBy("pardoned", "MANUAL_PARDON"), endedBy("removed", "REMOVE"), endedBy("revoked", "REVOKE"));
        Punishment pardoned = punishment(player, "pardoned");

        for (String id : List.of("removed", "revoked")) {
            Punishment ended = punishment(player, id);
            assertThat(ended.getModifications()).extracting(modification -> modification.type())
                .containsExactly(PunishmentModificationType.MANUAL_PARDON.name());
            assertThat(PunishmentState.activeUntil(ended)).isEqualTo(PunishmentState.activeUntil(pardoned))
                .isBefore(ImportTestContexts.NOW);
        }
    }

    @Test
    void theReasonLivesInTheDataAndIsNotRepeatedAsTheFirstNote() throws IOException {
        String note = "{\"text\":\"%s\",\"date\":{\"$date\":\"2026-01-01T00:00:00Z\"}}";
        MappedPlayer player = map(DefaultPunishmentTypes.getAll(),
            "{\"id\":\"stored\",\"typeOrdinal\":2,\"issued\":{\"$date\":\"2026-01-01T00:00:00Z\"},"
                + "\"data\":{\"enforcementCategory\":\"BAN\",\"reason\":\"Cheating\"},"
                + "\"notes\":[" + note.formatted("Cheating") + "," + note.formatted("informant is Alex") + "]}",
            "{\"id\":\"legacy\",\"typeOrdinal\":2,\"issued\":{\"$date\":\"2026-01-01T00:00:00Z\"},"
                + "\"data\":{\"enforcementCategory\":\"BAN\"},"
                + "\"notes\":[" + note.formatted("Spamming the chat") + "," + note.formatted("informant is Alex") + "]}");

        for (String id : List.of("stored", "legacy")) {
            assertThat(punishment(player, id).getNotes()).extracting(PunishmentNote::text).containsExactly("informant is Alex");
        }
        assertThat(punishment(player, "stored").data().reason()).isEqualTo("Cheating");
        assertThat(punishment(player, "legacy").data().reason()).isEqualTo("Spamming the chat");
    }

    private static String endedBy(String id, String modificationType) {
        return "{\"id\":\"" + id + "\",\"typeOrdinal\":2,\"issued\":{\"$date\":\"2026-01-01T00:00:00Z\"},"
            + "\"data\":{\"enforcementCategory\":\"BAN\"},\"modifications\":[{\"id\":\"m\",\"type\":\"" + modificationType
            + "\",\"date\":{\"$date\":\"2026-01-02T00:00:00Z\"}}]}";
    }

    @Test
    void theHistoryChoiceIsExplicitAndShowsDaysAndSpaceForBothOptions() throws IOException {
        ArchiveIndex index = ImportTestContexts.index(new HostedArchiveFixture()
            .line("chat_logs", "{\"uuid\":\"" + UUID + "\",\"message\":\"hi\",\"timestamp\":{\"$numberLong\":\""
                + ImportTestContexts.NOW.minus(Duration.ofDays(400)).toEpochMilli() + "\"}}"), directory);
        List<RetentionPlan> plans = RetentionPlanner.plans(index.retention(), DataRetentionSettings.defaults(),
            ReplayRetentionSettings.defaults(), null);

        RetentionPlan keep = plans.stream().filter(plan -> plan.choice() == RetentionChoice.KEEP_HISTORY).findFirst().orElseThrow();
        RetentionPlan standard = plans.stream().filter(plan -> plan.choice() == RetentionChoice.STANDARD).findFirst().orElseThrow();
        assertThat(keep.chatLogDays()).isGreaterThanOrEqualTo(430);
        assertThat(keep.rowsNotKept()).isZero();
        assertThat(keep.keepsIpHistory()).isTrue();
        assertThat(standard.chatLogDays()).isEqualTo(30);
        assertThat(standard.rowsNotKept()).isEqualTo(1);
        assertThat(standard.keepsIpHistory()).isFalse();
        assertThat(keep.estimatedBytes()).isGreaterThan(standard.estimatedBytes());
    }

    @Test
    void ipEntriesBeyondTheCutoffAreMappedOnlyWhenHistoryIsKept() throws IOException {
        Instant seen = ImportTestContexts.NOW.minus(Duration.ofDays(500));
        ArchiveIndex index = ImportTestContexts.index(new HostedArchiveFixture().line("players", "{\"_id\":\"p\",\"minecraftUuid\":\"" + UUID
            + "\",\"ipAddresses\":[{\"ipAddress\":\"203.0.113.9\",\"firstLogin\":{\"$date\":\"" + seen + "\"}}]}"), directory);

        assertThat(index.retention().ipEntriesBeyondCutoff()).isEqualTo(1);
        MappedPlayer standard = new PlayerMapper(ImportTestContexts.context(index)).map(document("{\"_id\":\"p\",\"minecraftUuid\":\""
            + UUID + "\",\"ipAddresses\":[{\"ipAddress\":\"203.0.113.9\",\"firstLogin\":{\"$date\":\"" + seen + "\"}}]}")).orElseThrow();
        assertThat(standard.snapshot().player().getIpAddresses()).isEmpty();
        MappedPlayer kept = new PlayerMapper(ImportTestContexts.context(index, DefaultPunishmentTypes.getAll(), Map.of(), true))
            .map(document("{\"_id\":\"p\",\"minecraftUuid\":\"" + UUID + "\",\"ipAddresses\":[{\"ipAddress\":\"203.0.113.9\","
                + "\"firstLogin\":{\"$date\":\"" + seen + "\"}}]}")).orElseThrow();
        assertThat(kept.snapshot().player().getIpAddresses()).hasSize(1);
    }

    @Test
    void anIpEntryThatIsNotAnAddressLiteralIsReportedAndAPaddedOneIsStoredTrimmed() throws IOException {
        Instant seen = ImportTestContexts.NOW.minus(Duration.ofDays(1));
        ArchiveIndex index = ImportTestContexts.index(new HostedArchiveFixture(), directory);
        ImportContext context = ImportTestContexts.context(index);
        MappedPlayer mapped = new PlayerMapper(context).map(document("{\"_id\":\"p\",\"minecraftUuid\":\"" + UUID
            + "\",\"ipAddresses\":[" + ipEntry("play.example.com", seen) + "," + ipEntry("x".repeat(60), seen) + ","
            + ipEntry(" 203.0.113.9 ", seen) + "]}")).orElseThrow();

        assertThat(mapped.snapshot().player().getIpAddresses()).extracting(IPEntry::getIpAddress)
            .containsExactly("203.0.113.9");
        assertThat(context.issues().tally().issues().get(IssueCode.IP_INVALID).count()).isEqualTo(2);
    }

    private static String ipEntry(String address, Instant seen) {
        return "{\"ipAddress\":\"" + address + "\",\"firstLogin\":{\"$date\":\"" + seen + "\"}}";
    }

    private MappedPlayer map(List<PunishmentType> types, String... punishments) throws IOException {
        ArchiveIndex index = ImportTestContexts.index(new HostedArchiveFixture(), directory);
        ImportContext context = ImportTestContexts.context(index, types, Map.of(), false);
        MappedPlayer mapped = new PlayerMapper(context).map(document("{\"_id\":\"p\",\"minecraftUuid\":\"" + UUID
            + "\",\"punishments\":[" + String.join(",", punishments) + "]}")).orElseThrow();
        assertThat(context.issues().tally().issues()).doesNotContainKey(IssueCode.RECORD_UNREADABLE);
        return mapped;
    }

    private static Punishment punishment(MappedPlayer player, String id) {
        return player.snapshot().player().getPunishments().stream().filter(punishment -> punishment.getId().equals(id)).findFirst().orElseThrow();
    }

    private static ArchiveDocument document(String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return ArchiveDocument.of(ExtendedJsonDecoder.decodeLine(bytes, bytes.length));
    }
}
