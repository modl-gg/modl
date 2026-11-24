package gg.modl.backend.migration.service;

import static gg.modl.backend.database.jooq.Tables.PLAYER_NOTES;
import static gg.modl.backend.database.jooq.Tables.PLAYER_USERNAMES;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENTS;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_MODIFICATIONS;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_NOTES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import gg.modl.backend.database.TextLimits;
import gg.modl.backend.migration.validation.MigrationValidator;
import gg.modl.backend.player.data.IPEntry;
import gg.modl.backend.player.data.NoteEntry;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.PlayerNoteKind;
import gg.modl.backend.player.data.UsernameEntry;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentIssuerKind;
import gg.modl.backend.player.data.punishment.PunishmentModification;
import gg.modl.backend.player.data.punishment.PunishmentNote;
import gg.modl.backend.player.data.punishment.PunishmentState;
import gg.modl.backend.player.data.punishment.ResolvedCategory;
import gg.modl.backend.settings.data.PunishmentType;
import java.math.BigInteger;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MigrationRecordMapperTest {
    private static final Instant IMPORTED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final String ISSUED = "2025-03-01T10:00:00Z";
    private static final String PLAYER = "11111111-2222-3333-4444-555555555555";
    private static final Map<Integer, PunishmentType> TYPES = Map.of(
        1, PunishmentType.builder().ordinal(1).name("Mute").build(),
        2, PunishmentType.builder().ordinal(2).name("Ban").build(),
        20, PunishmentType.builder().ordinal(20).name("Bad Name").permanentUntilUsernameChange(true).build());

    private final MigrationRecordMapper mapper = new MigrationRecordMapper(new MigrationValidator());

    @Test
    void aPlayerNoteKeepsItsNamedKind() {
        Player imported = map(PLAYER, Map.of("notes", List.of(
            Map.of("text", "stop spamming", "date", ISSUED, "issuerName", "Mod", "kind", "warning"),
            Map.of("text", "WARNING: kept as typed", "date", ISSUED, "issuerName", "Mod", "kind", "NOTE"))));

        assertThat(imported.getNotes()).extracting(NoteEntry::getKind, NoteEntry::getText).containsExactly(
            tuple(PlayerNoteKind.WARNING, "stop spamming"),
            tuple(PlayerNoteKind.NOTE, "WARNING: kept as typed"));
    }

    @Test
    void aPlayerNoteWithoutAKindIsReadTheWayHostedStoredWarnings() {
        Player imported = map(PLAYER, Map.of("notes", List.of(
            Map.of("text", "WARNING: stop spamming", "date", ISSUED, "issuerName", "Mod"),
            Map.of("text", "watch this player", "date", ISSUED, "issuerName", "Mod", "kind", "unknown"))));

        assertThat(imported.getNotes()).extracting(NoteEntry::getKind, NoteEntry::getText).containsExactly(
            tuple(PlayerNoteKind.WARNING, "stop spamming"),
            tuple(PlayerNoteKind.NOTE, "watch this player"));
    }

    @Test
    void anIssuerIdLongerThanItsColumnIsDroppedAndTheRecordIsKept() {
        String overlong = "i".repeat(Stream.of(PUNISHMENTS.ISSUER_ID, PUNISHMENT_MODIFICATIONS.ISSUER_ID, PUNISHMENT_NOTES.ISSUER_ID,
            PLAYER_NOTES.ISSUER_ID).mapToInt(TextLimits::limit).max().orElseThrow() + 16);
        Map<String, Object> issuer = Map.of("issuerName", "Mod", "issuerId", overlong, "date", ISSUED);
        Player imported = map(PLAYER, Map.of(
            "notes", List.of(merged(issuer, Map.of("text", "watch this player"))),
            "punishments", List.of(Map.of("typeOrdinal", 2, "issued", ISSUED, "issuerName", "Mod", "issuerId", overlong,
                "reason", "Cheating",
                "modifications", List.of(merged(issuer, Map.of("type", "MANUAL_PARDON"))),
                "notes", List.of(merged(issuer, Map.of("text", "informant is Alex")))))));

        assertThat(imported.getNotes()).extracting(NoteEntry::getIssuerName, NoteEntry::getIssuerId).containsExactly(tuple("Mod", null));
        Punishment punishment = imported.getPunishments().getFirst();
        assertThat(punishment.getIssuerId()).isNull();
        assertThat(punishment.getModifications()).extracting(PunishmentModification::issuerId).containsExactly((String) null);
        assertThat(punishment.getNotes()).extracting(PunishmentNote::issuerId).containsExactly((String) null);
    }

    @Test
    void anIssuerIdThatFitsItsColumnIsKept() {
        String id = "s".repeat(TextLimits.limit(PLAYER_NOTES.ISSUER_ID));
        Player imported = map(PLAYER, Map.of("notes", List.of(
            Map.of("text", "watch this player", "date", ISSUED, "issuerId", id))));

        assertThat(imported.getNotes()).extracting(NoteEntry::getIssuerId).containsExactly(id);
    }

    private static Map<String, Object> merged(Map<String, Object> first, Map<String, Object> second) {
        Map<String, Object> result = new HashMap<>(first);
        result.putAll(second);
        return result;
    }

    @Test
    void everyPunishmentGetsAResolvedCategory() {
        assertThat(punishment(Map.of("typeOrdinal", 2, "issued", ISSUED)).enforcementCategory()).isEqualTo(ResolvedCategory.BAN);
        assertThat(punishment(Map.of("typeOrdinal", "1", "issued", ISSUED)).enforcementCategory()).isEqualTo(ResolvedCategory.MUTE);
        assertThat(punishment(Map.of("typeOrdinal", 999, "issued", ISSUED, "data", Map.of("enforcementCategory", "BAN")))
            .enforcementCategory()).isEqualTo(ResolvedCategory.NONE);
    }

    @Test
    void theIssuerKindFollowsTheImportedIssuer() {
        assertThat(punishment(Map.of("typeOrdinal", 2, "issued", ISSUED, "issuerName", "Console")).getIssuerKind())
            .isEqualTo(PunishmentIssuerKind.CONSOLE);
        assertThat(punishment(Map.of("typeOrdinal", 2, "issued", ISSUED, "issuerName", "Mod")).getIssuerKind())
            .isEqualTo(PunishmentIssuerKind.STAFF);
        Punishment withoutIssuer = punishment(Map.of("typeOrdinal", 2, "issued", ISSUED));
        assertThat(withoutIssuer.getIssuerKind()).isEqualTo(PunishmentIssuerKind.UNKNOWN);
        assertThat(withoutIssuer.getIssuerName()).isEqualTo("Unknown");
    }

    @Test
    void aCompletedStatWipeWithoutATimeGetsTheImportTime() {
        Punishment imported = punishment(Map.of("typeOrdinal", 2, "issued", ISSUED, "data", Map.of("statWipeCompleted", true)));

        assertThat(imported.data().statWipeCompletedAt()).isEqualTo(IMPORTED_AT);
    }

    @Test
    void aPunishmentInactiveAtTheSourceIsImportedPardoned() {
        Punishment imported = punishment(Map.of("typeOrdinal", 2, "issued", ISSUED, "data", Map.of("active", false)));

        assertThat(imported.isPardoned()).isTrue();
        assertThat(PunishmentState.activeUntil(imported)).isEqualTo(PunishmentState.INACTIVE);
    }

    @Test
    void aManualPardonFromThePluginKeepsItsIssuerAndReasonAndMarksThePunishmentPardoned() {
        Punishment imported = punishment(Map.of("typeOrdinal", 2, "issued", ISSUED, "data", Map.of("active", false),
            "modifications", List.of(Map.of("type", "MANUAL_PARDON", "date", "2025-03-02T10:00:00Z",
                "issuerName", "Admin", "reason", "Appeal accepted"))));

        assertThat(imported.getModifications()).singleElement().satisfies(modification -> {
            assertThat(modification.type()).isEqualTo("MANUAL_PARDON");
            assertThat(modification.issuerName()).isEqualTo("Admin");
            assertThat(modification.reason()).isEqualTo("Appeal accepted");
        });
        assertThat(imported.data().status()).isEqualTo("Pardoned");
        assertThat(PunishmentState.activeUntil(imported)).isEqualTo(PunishmentState.INACTIVE);
    }

    @Test
    void anImportedAltBlockingBanKeepsAltBlocking() {
        Punishment imported = punishment(Map.of("typeOrdinal", 2, "issued", ISSUED, "data", Map.of("altBlocking", true)));

        assertThat(imported.data().altBlocking()).isTrue();
    }

    @Test
    void anImportedRollbackIsNotTurnedIntoAPardon() {
        Punishment imported = punishment(Map.of("typeOrdinal", 2, "issued", ISSUED, "data", Map.of("active", false),
            "modifications", List.of(Map.of("type", "ROLLBACK", "date", ISSUED))));

        assertThat(imported.getModifications()).singleElement()
            .satisfies(modification -> assertThat(modification.type()).isEqualTo("ROLLBACK"));
        assertThat(imported.isPardoned()).isFalse();
        assertThat(PunishmentState.activeUntil(imported)).isEqualTo(PunishmentState.INACTIVE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REMOVE", "REVOKE"})
    void aLegacyRemovalArrivesAsAPardonThatEndsEnforcement(String legacyType) {
        Punishment imported = punishment(Map.of("typeOrdinal", 2, "issued", ISSUED,
            "modifications", List.of(Map.of("type", legacyType, "date", ISSUED))));

        assertThat(imported.getModifications()).singleElement()
            .satisfies(modification -> assertThat(modification.type()).isEqualTo("MANUAL_PARDON"));
        assertThat(imported.isPardoned()).isTrue();
        assertThat(PunishmentState.activeUntil(imported)).isEqualTo(PunishmentState.INACTIVE);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void aLegacyReasonKeptOnlyInTheFirstNoteBecomesTheReason(int typeOrdinal) {
        Punishment imported = punishment(withNotes(typeOrdinal,
            List.of(note("Spamming the chat", ISSUED), note("informant is Alex", ISSUED))));

        assertThat(imported.data().reason()).isEqualTo("Spamming the chat");
        assertThat(imported.getNotes()).extracting(PunishmentNote::text).containsExactly("informant is Alex");
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4, 5, 6, 18})
    void theNoteOfAnyOtherTypeStaysANote(int typeOrdinal) {
        assertThat(punishment(withNotes(typeOrdinal, List.of(note("informant is Alex", ISSUED)))).data().reason()).isNull();
    }

    @Test
    void aStaffNoteWrittenAfterTheIssueIsNotTakenForAReason() {
        Map<String, Object> source = withNotes(2, List.of(note("informant is Alex", "2025-03-02T10:00:00Z")));

        assertThat(punishment(source).data().reason()).isNull();
    }

    @Test
    void aStaffNoteBehindTheGeneratedIssueNoteIsNotTakenForAReason() {
        Map<String, Object> source = withNotes(2, List.of(note("issued permanent ban", ISSUED), note("informant is Alex", ISSUED)));

        assertThat(punishment(source).data().reason()).isNull();
    }

    @Test
    void aStoredReasonWinsOverTheFirstNote() {
        Punishment imported = punishment(Map.of("typeOrdinal", 2, "issued", ISSUED,
            "data", Map.of("reason", "Cheating"), "notes", List.of(note("informant is Alex", ISSUED))));

        assertThat(imported.data().reason()).isEqualTo("Cheating");
        assertThat(imported.getNotes()).extracting(PunishmentNote::text).containsExactly("informant is Alex");
    }

    @Test
    void aFirstNoteThatRepeatsTheStoredReasonIsNotImportedTwice() {
        Punishment imported = punishment(Map.of("typeOrdinal", 2, "issued", ISSUED,
            "data", Map.of("reason", "Cheating"), "notes", List.of(note("Cheating", ISSUED), note("informant is Alex", ISSUED))));

        assertThat(imported.data().reason()).isEqualTo("Cheating");
        assertThat(imported.getNotes()).extracting(PunishmentNote::text).containsExactly("informant is Alex");
    }

    @Test
    void theRestoredReasonDoesNotChangeTheDerivedIdOfAReimport() {
        Map<String, Object> withoutNotes = Map.of("typeOrdinal", 2, "issued", ISSUED);

        assertThat(punishment(withNotes(2, List.of(note("Cheating", ISSUED)))).getId()).isEqualTo(punishment(withoutNotes).getId());
    }

    private static Map<String, Object> withNotes(int typeOrdinal, List<Map<String, Object>> notes) {
        return Map.of("typeOrdinal", typeOrdinal, "issued", ISSUED, "notes", notes);
    }

    private static Map<String, Object> note(String text, String date) {
        return Map.of("text", text, "date", date, "issuerName", "Mod");
    }

    @Test
    void anEnormousDurationSaturatesInsteadOfWrapping() {
        Punishment imported = punishment(Map.of("typeOrdinal", 2, "issued", ISSUED, "started", ISSUED,
            "duration", BigInteger.TWO.pow(70)));

        assertThat(imported.data().duration()).isEqualTo(Long.MAX_VALUE);
        assertThat(PunishmentState.activeUntil(imported)).isEqualTo(PunishmentState.MAX);
    }

    @Test
    void aBlankIdIsDerivedStablyPerPlayer() {
        Map<String, Object> source = Map.of("typeOrdinal", 2, "issued", ISSUED, "id", " ");

        String first = punishment(source).getId();

        assertThat(punishment(source).getId()).isEqualTo(first);
        assertThat(map(UUID.randomUUID().toString(), Map.of("punishments", List.of(source))).getPunishments().getFirst().getId())
            .isNotEqualTo(first);
    }

    @Test
    void ipLoginsBecomeFirstSeenLastSeenAndCount() {
        Player player = map(PLAYER, Map.of("ipList", List.of(Map.of(
            "ipAddress", "203.0.113.7",
            "firstLogin", "2025-02-01T00:00:00Z",
            "logins", List.of("2025-03-01T00:00:00Z", "2025-01-01T00:00:00Z", "2025-02-15T00:00:00Z")))));

        IPEntry address = player.getIpAddresses().getFirst();
        assertThat(address.getFirstLogin()).isEqualTo(Instant.parse("2025-01-01T00:00:00Z"));
        assertThat(address.getLastLogin()).isEqualTo(Instant.parse("2025-03-01T00:00:00Z"));
        assertThat(address.getLoginCount()).isEqualTo(3L);
    }

    @Test
    void usernamesAreOrderedByDateAndFittedToTheColumn() {
        int limit = TextLimits.limit(PLAYER_USERNAMES.USERNAME);
        Player player = map(PLAYER, Map.of("usernames", List.of(
            Map.of("username", "n".repeat(limit + 10), "date", "2025-02-01T00:00:00Z"),
            Map.of("username", "Older", "date", "2025-01-01T00:00:00Z"))));

        assertThat(player.getUsernames()).extracting(UsernameEntry::username).containsExactly("Older", "n".repeat(limit));
    }

    @Test
    void anIdentityRestrictionLosesAltBlockingAndWipeAndKeepsItsTerms() {
        Punishment restriction = punishment(Map.of("typeOrdinal", 20, "issued", ISSUED, "data",
            Map.of("altBlocking", true, "wipeAfterExpiry", true, "blockedName", "BadName", "duration", 5000)));
        Punishment ban = punishment(Map.of("typeOrdinal", 2, "issued", ISSUED, "data",
            Map.of("altBlocking", true, "wipeAfterExpiry", true)));

        assertThat(restriction.data().altBlocking()).isFalse();
        assertThat(restriction.data().wipeAfterExpiry()).isFalse();
        assertThat(restriction.data().blockedName()).isEqualTo("BadName");
        assertThat(restriction.data().duration()).isEqualTo(5000L);
        assertThat(restriction.enforcementCategory()).isEqualTo(ResolvedCategory.BAN);
        assertThat(ban.data().altBlocking()).isTrue();
        assertThat(ban.data().wipeAfterExpiry()).isTrue();
    }

    @Test
    void aFileCannotPutPlayersOnlineOrLinkAccounts() {
        Player player = map(PLAYER, Map.of("data", Map.of(
            "isOnline", true, "linkedAccounts", List.of(UUID.randomUUID().toString()))));

        assertThat(player.data().isOnline()).isFalse();
        assertThat(player.data().linkedAccountUuids()).isEmpty();
    }

    private Punishment punishment(Map<String, Object> source) {
        return map(PLAYER, Map.of("punishments", List.of(source))).getPunishments().getFirst();
    }

    private Player map(String uuid, Map<String, Object> source) {
        return mapper.toPlayer(uuid, source, TYPES, IMPORTED_AT);
    }
}
