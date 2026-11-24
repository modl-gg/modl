package gg.modl.backend.player.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.player.data.punishment.PunishmentIssuerKind;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentEvidence;
import gg.modl.backend.player.data.punishment.PunishmentModification;
import gg.modl.backend.player.data.punishment.PunishmentNote;
import gg.modl.backend.player.dto.response.PlayerStandingView;
import gg.modl.backend.player.dto.response.PlayerStandingView.Entry;
import gg.modl.backend.player.dto.response.PlayerStandingView.State;
import gg.modl.backend.player.repository.PlayerRepository;
import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.OffenderThresholdSettings;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.service.OffenderThresholdSettingsService;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.support.ApplicationJson;
import gg.modl.backend.support.TestServers;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PlayerStandingServiceTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String UUID_TEXT = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String DESCRIPTION = "Follow the server rules";
    private static final long DAY = Duration.ofDays(1).toMillis();
    private static final int KICK = 0;
    private static final int BAN = 2;
    private static final int CHEATING = 30;

    private final Server server = TestServers.server("server", "admin@example.com");
    private final PlayerRepository players = mock(PlayerRepository.class);
    private final PunishmentRepository punishments = mock(PunishmentRepository.class);
    private final PunishmentTypeService types = mock(PunishmentTypeService.class);
    private final OffenderThresholdSettingsService thresholds = mock(OffenderThresholdSettingsService.class);
    private final PlayerStandingService service = new PlayerStandingService(players, punishments, types,
        new PlayerStatusCalculator(types, thresholds, Clock.fixed(NOW, ZoneOffset.UTC)));

    @BeforeEach
    void setUp() {
        when(players.findByMinecraftUuid(UUID_TEXT)).thenReturn(Optional.of(
            Player.builder().id("player").minecraftUuid(UUID.fromString(UUID_TEXT)).build()));
        when(types.getPunishmentTypes(server)).thenReturn(List.of(
            type(KICK, "Kick", "Administrative"), type(1, "Manual Mute", "Administrative"),
            type(BAN, "Manual Ban", "Administrative"), type(CHEATING, "Cheating", "Gameplay")));
        when(types.getPunishmentTypeName(any(), anyInt())).thenReturn("Cheating");
        when(thresholds.getThresholdSettings(any())).thenReturn(OffenderThresholdSettings.builder()
            .gameplay(new OffenderThresholdSettings.CategoryThresholds(4, 12, 6))
            .social(new OffenderThresholdSettings.CategoryThresholds(4, 8, 6))
            .build());
    }

    @Test
    void nothingStaffOnlyLeavesTheBackend() {
        Map<String, Object> data = new HashMap<>(Map.of(
            "enforcementCategory", "BAN",
            "reason", "SECRET internal reason",
            "severity", "SECRET severity",
            "blockedName", "SECRET blocked name",
            "linkedBanId", "SECRET linked ban",
            "linkedBanParentUuid", "SECRET parent account"));
        Punishment punishment = new Punishment("ABCD1234", CHEATING, "SECRET issuer", "SECRET issuer id", PunishmentIssuerKind.STAFF, NOW, NOW,
            new ArrayList<>(List.of(new PunishmentModification("SECRET modification id", "MANUAL_PARDON", NOW,
                "SECRET pardoner", "SECRET pardoner id", "SECRET pardon reason", null, "SECRET appeal ticket", null))),
            new ArrayList<>(List.of(new PunishmentNote("SECRET note id", "SECRET note", NOW, "SECRET author", null))),
            new ArrayList<>(List.of(new PunishmentEvidence("SECRET evidence", "SECRET url", "SECRET type", "SECRET uploader",
                "SECRET uploader id", NOW, "SECRET file", "SECRET file type", 4242L))),
            new ArrayList<>(List.of("SECRET ticket")), data);
        when(punishments.findByPlayer("player")).thenReturn(List.of(punishment));

        String json = ApplicationJson.MAPPER.writeValueAsString(service.standingOf(server, UUID_TEXT));

        assertThat(json).doesNotContain("SECRET").doesNotContain("issuer", "notes", "evidence", "attachedTicketIds", "\"data\"");
        assertThat(json).contains(DESCRIPTION);
    }

    @ParameterizedTest
    @CsvSource({"1, typed for the player", "2, typed for the player", "30, Follow the server rules"})
    void theReasonIsTheOneMeantForThePlayer(int ordinal, String shown) {
        given(punishment("P1", ordinal, NOW, NOW, null, "typed for the player"));

        assertThat(onlyEntry().reason()).isEqualTo(shown);
    }

    @Test
    void aTypeWithoutADescriptionLeavesTheDefaultToThePlayersLanguage() {
        given(punishment("P1", 40, NOW, NOW, null, "internal"));

        assertThat(onlyEntry().reason()).isNull();
    }

    @Test
    void aRunningPunishmentShowsItsLengthAndExpiry() {
        given(punishment("P1", BAN, NOW, NOW, DAY, null));

        Entry entry = onlyEntry();
        assertThat(entry.state()).isEqualTo(State.ACTIVE);
        assertThat(entry.category()).isEqualTo("BAN");
        assertThat(entry.durationMs()).isEqualTo(DAY);
        assertThat(entry.expiresAt()).isEqualTo(NOW.toEpochMilli() + DAY);
    }

    @Test
    void anExpiredPunishmentIsInactive() {
        Instant started = NOW.minus(Duration.ofDays(2));
        given(punishment("P1", BAN, started, started, DAY, null));

        assertThat(onlyEntry().state()).isEqualTo(State.INACTIVE);
    }

    @Test
    void aPunishmentThatHasNotStartedIsQueued() {
        given(punishment("P1", BAN, NOW, null, DAY, null));

        assertThat(onlyEntry().state()).isEqualTo(State.QUEUED);
    }

    @ParameterizedTest
    @CsvSource({"MANUAL_PARDON, PARDONED", "SYSTEM_PARDON, PARDONED", "ROLLBACK, ROLLED_BACK"})
    void anEndedPunishmentSaysWhenAndHowItEnded(String modification, State state) {
        Punishment ended = punishment("P1", BAN, NOW, NOW, null, null);
        ended.getModifications().add(change(modification, null));
        given(ended);

        Entry entry = onlyEntry();
        assertThat(entry.state()).isEqualTo(state);
        assertThat(entry.endedAt()).isEqualTo(NOW.toEpochMilli());
    }

    @Test
    void theLatestDurationChangeWins() {
        Punishment changed = punishment("P1", BAN, NOW, NOW, DAY, null);
        changed.getModifications().add(change("MANUAL_DURATION_CHANGE", 3 * DAY));
        changed.getModifications().add(change("APPEAL_DURATION_CHANGE", 2 * DAY));
        given(changed);

        assertThat(onlyEntry().durationMs()).isEqualTo(2 * DAY);
    }

    @Test
    void kicksAreLeftOutAndTheNewestFourteenRemain() {
        List<Punishment> history = new ArrayList<>(IntStream.range(0, 20)
            .mapToObj(index -> punishment("P" + index, BAN, NOW.minusSeconds(100 - index), NOW, null, null))
            .toList());
        history.add(punishment("KICKED", KICK, NOW, NOW, null, null));
        given(history.toArray(Punishment[]::new));

        assertThat(service.standingOf(server, UUID_TEXT).punishments()).extracting(Entry::id)
            .containsExactlyElementsOf(IntStream.range(0, 14).mapToObj(index -> "P" + (19 - index)).toList());
    }

    @Test
    void thePlayersPointsAndLevelsComeWithTheStanding() {
        when(types.getPunishmentTypes(server)).thenReturn(List.of(PunishmentType.builder()
            .ordinal(CHEATING).name("Cheating").category("Gameplay").customPoints(6).build()));
        given(punishment("P1", CHEATING, NOW, NOW, null, null));

        PlayerStandingView standing = service.standingOf(server, UUID_TEXT);

        assertThat(standing.gameplayPoints()).isEqualTo(6);
        assertThat(standing.socialPoints()).isZero();
    }

    @Test
    void onlyThePlayerHeaderIsLoadedNotTheirNotesOrAddresses() {
        given();

        service.standingOf(server, UUID_TEXT.toUpperCase(Locale.ROOT));

        verify(players).findByMinecraftUuid(UUID_TEXT);
    }

    @Test
    void anUnknownPlayerIsNotFound() {
        assertThatThrownBy(() -> service.standingOf(server, UUID.randomUUID().toString()))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    private void given(Punishment... stored) {
        when(punishments.findByPlayer("player")).thenReturn(List.of(stored));
    }

    private Entry onlyEntry() {
        return service.standingOf(server, UUID_TEXT).punishments().getFirst();
    }

    private static PunishmentType type(int ordinal, String name, String category) {
        return PunishmentType.builder().ordinal(ordinal).name(name).category(category).playerDescription(DESCRIPTION).build();
    }

    private static PunishmentModification change(String type, @Nullable Long duration) {
        return new PunishmentModification("M", type, NOW, "Mod", null, "", duration, null, null);
    }

    private static Punishment punishment(String id, int ordinal, Instant issued, @Nullable Instant started,
                                         @Nullable Long duration, @Nullable String reason) {
        Map<String, Object> data = new HashMap<>(Map.of("enforcementCategory", "BAN"));
        if (duration != null) {
            data.put("duration", duration);
        }
        if (reason != null) {
            data.put("reason", reason);
        }
        return new Punishment(id, ordinal, "Mod", null, PunishmentIssuerKind.STAFF, issued, started,
            new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), data);
    }
}
