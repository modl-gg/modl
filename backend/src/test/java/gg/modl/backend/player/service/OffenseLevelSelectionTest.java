package gg.modl.backend.player.service;

import static gg.modl.backend.player.PunishmentStore.BAN;
import static gg.modl.backend.player.PunishmentStore.draft;
import static gg.modl.backend.player.service.LifecycleHarness.NOW;
import static gg.modl.backend.player.service.LifecycleHarness.PLAYER_ID;
import static gg.modl.backend.player.service.LifecycleHarness.PLAYER_UUID;
import static gg.modl.backend.player.service.PunishmentTypesFixture.CONFIGURED;
import static gg.modl.backend.player.service.PunishmentTypesFixture.NAME_RESTRICTED;
import static gg.modl.backend.player.service.PunishmentTypesFixture.SINGLE;
import static gg.modl.backend.player.service.PunishmentTypesFixture.ZERO_LENGTH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.player.data.punishment.PunishmentIssuerKind;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentStatus;
import gg.modl.backend.player.dto.request.CreatePunishmentRequest;
import gg.modl.backend.player.dto.request.MinecraftCreatePunishmentRequest;
import gg.modl.backend.player.dto.response.PunishmentPreviewView;
import gg.modl.backend.player.dto.response.PunishmentSeverityPreviewView;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.DurationDetail;
import gg.modl.backend.settings.data.OffenseLevelDurations;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.storage.service.EvidenceUploadTokenService;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.ticket.repository.TicketRepository;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class OffenseLevelSelectionTest {
    private static final long DAY = Duration.ofDays(1).toMillis();
    private static final long PERMANENT = -1L;
    private static final Map<String, Long> PANEL_UNIT_MILLIS = Map.of("days", DAY, "months", 30 * DAY);
    private static final PunishmentIssuer STAFF = PunishmentIssuer.staff("Staff", "staff-1");

    private final LifecycleHarness harness = new LifecycleHarness();
    private final Server server = TestServers.server("server", "admin@example.com");

    @ParameterizedTest
    @CsvSource({
        "31, single, 0, habitual, habitual, habitual, -1",
        "31, single, 0, medium, medium, medium, 14",
        "31, single, 3, low, first, low, 3",
        "31, single, 3, medium, medium, medium, 14",
        "31, single, 1, habitual, habitual, habitual, -1",
        "30, severe, 0, habitual, habitual, habitual, 28",
        "30, severe, 3, low, first, low, 7",
        "30, severe, 3, medium, medium, medium, 14",
        "30, lenient, 1, first, first, low, 1",
        "30, regular, 0, medium, medium, medium, 6"
    })
    void aSelectedLevelBeatsHistoryInBothDirections(int ordinal, String severity, int priorOffenses, String selected,
                                                    String level, String displayed, long days) {
        givenPriorOffenses(priorOffenses);

        Punishment created = createFromPanel(ordinal, severity, selected, null);

        assertThat(created.data().offenseLevel()).isEqualTo(level);
        assertThat(created.data().status()).isEqualTo(displayed);
        assertThat(created.data().duration()).isEqualTo(days < 0 ? PERMANENT : days * DAY);
    }

    @ParameterizedTest
    @CsvSource({
        "31, single, 0, first, 3",
        "31, single, 1, medium, 14",
        "31, single, 3, habitual, -1",
        "30, severe, 0, first, 7",
        "30, severe, 1, medium, 14",
        "30, severe, 3, habitual, 28"
    })
    void withoutASelectionHistoryDecides(int ordinal, String severity, int priorOffenses, String level, long days) {
        givenPriorOffenses(priorOffenses);

        Punishment created = createFromPanel(ordinal, severity, null, null);

        assertThat(created.data().offenseLevel()).isEqualTo(level);
        assertThat(created.data().duration()).isEqualTo(days < 0 ? PERMANENT : days * DAY);
    }

    @ParameterizedTest
    @ValueSource(ints = {SINGLE, CONFIGURED})
    void thePanelPreviewFormulaAndTheStoredDurationAgree(int ordinal) {
        PunishmentType type = harness.types.types().getPunishmentTypeByOrdinal(server, ordinal).orElseThrow();
        List<String> severities = ordinal == SINGLE ? List.of("single") : List.of("lenient", "regular", "severe");
        givenPriorOffenses(3);

        for (String severity : severities) {
            for (String level : List.of("first", "medium", "habitual")) {
                Punishment created = createFromPanel(ordinal, severity, level, null);

                assertThat(created.data().duration()).as("%s %s", severity, level)
                    .isEqualTo(panelPreviewMillis(configuredDurations(type, severity).getForOffenseLevel(level)));
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"0, first", "1, medium", "3, habitual"})
    void theBackendPreviewAgreesWithTheStoredDurationAtTheHistoryLevel(int priorOffenses, String level) {
        givenPriorOffenses(priorOffenses);
        PunishmentQueryService queries = queries();
        PunishmentPreviewView multi = queries.previewPunishment(server, PLAYER_UUID.toString(), CONFIGURED);
        PunishmentPreviewView single = queries.previewPunishment(server, PLAYER_UUID.toString(), SINGLE);

        assertStoredMatches(multi.getLenient(), CONFIGURED, "lenient", level);
        assertStoredMatches(multi.getRegular(), CONFIGURED, "regular", level);
        assertStoredMatches(multi.getAggravated(), CONFIGURED, "severe", level);
        assertStoredMatches(single.getSingleSeverity(), SINGLE, "single", level);
    }

    @ParameterizedTest
    @CsvSource({"panel", "minecraft", "automatic"})
    void everyRouteWithoutALevelOrSeverityLetsHistoryDecide(String route) {
        givenPriorOffenses(1);
        CreatePunishmentRequest request = new CreatePunishmentRequest("Mod", null, PunishmentIssuerKind.STAFF, CONFIGURED, null, null, null, null,
            null, null, "Rule broken", null);

        switch (route) {
            case "panel" -> harness.lifecycle.createPanelPunishment(server, PLAYER_UUID, STAFF, request);
            case "minecraft" -> harness.lifecycle.createMinecraftPunishment(server, new MinecraftCreatePunishmentRequest(
                PLAYER_UUID.toString(), "Mod", null, CONFIGURED, "Rule broken", null, null, null, null, null, null, true));
            default -> harness.lifecycle.createPunishment(server, PLAYER_UUID, request);
        }

        assertThat(harness.inserted().data().offenseLevel()).isEqualTo("medium");
        assertThat(harness.inserted().data().duration()).isEqualTo(6 * DAY);
    }

    @Test
    void aSingleSeverityTypeFromAThirdPartyClientEscalatesWithoutASeverity() {
        givenPriorOffenses(3);

        harness.lifecycle.createMinecraftPunishment(server, new MinecraftCreatePunishmentRequest(
            PLAYER_UUID.toString(), "Bot", null, SINGLE, "Rule broken", null, null, null, null, null, null, true));

        assertThat(harness.inserted().data().offenseLevel()).isEqualTo("habitual");
        assertThat(harness.inserted().data().duration()).isEqualTo(PERMANENT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"first", "low", "medium", "habitual"})
    void thePreviewOfAPickedLevelShowsWhatIsStoredAndStillReportsTheHistoryLevel(String picked) {
        givenPriorOffenses(1);
        PunishmentQueryService queries = queries();
        PunishmentPreviewView multi = queries.previewPunishment(server, PLAYER_UUID.toString(), CONFIGURED, picked);
        PunishmentPreviewView single = queries.previewPunishment(server, PLAYER_UUID.toString(), SINGLE, picked);

        assertThat(createFromPanel(CONFIGURED, "lenient", picked, null).data().duration())
            .isEqualTo(multi.getLenient().getDurationMs());
        assertThat(createFromPanel(CONFIGURED, "regular", picked, null).data().duration())
            .isEqualTo(multi.getRegular().getDurationMs());
        assertThat(createFromPanel(CONFIGURED, "severe", picked, null).data().duration())
            .isEqualTo(multi.getAggravated().getDurationMs());
        assertThat(createFromPanel(SINGLE, "single", picked, null).data().duration())
            .isEqualTo(single.getSingleSeverity().getDurationMs());
        assertThat(single.getOffenderStatus()).isEqualTo("medium");
        assertThat(multi.getOffenderStatus()).isEqualTo("medium");
    }

    @Test
    void aConfiguredLengthOfZeroIsPreviewedAsThePermanentPunishmentItBecomes() {
        PunishmentPreviewView preview = queries().previewPunishment(server, PLAYER_UUID.toString(), ZERO_LENGTH);

        assertThat(preview.getRegular().isPermanent()).isTrue();
        assertThat(preview.getRegular().getDurationFormatted()).isEqualTo("Permanent");
        assertThat(createFromPanel(ZERO_LENGTH, "regular", null, null).data().duration()).isNull();
    }

    @Test
    void anIdentityRestrictionIsPreviewedAsABanWithoutATimer() {
        PunishmentPreviewView preview = queries().previewPunishment(server, PLAYER_UUID.toString(), NAME_RESTRICTED);

        assertThat(preview.getSingleSeverity().isPermanent()).isTrue();
        assertThat(preview.getSingleSeverity().getPunishmentType()).isEqualTo("ban");
        assertThat(preview.getSingleSeverity().getDurationMs()).isEqualTo(PERMANENT);
    }

    private PunishmentQueryService queries() {
        return new PunishmentQueryService(harness.clock, harness.players, harness.punishments, harness.statusCalculator,
            harness.types.types(), harness.types.thresholds(), mock(EvidenceUploadTokenService.class), harness.issuers,
            mock(TicketRepository.class));
    }

    @Test
    void anExplicitDurationStillWinsButKeepsTheSelectedLevel() {
        Punishment created = createFromPanel(CONFIGURED, "severe", "habitual", 5 * DAY);

        assertThat(created.data().duration()).isEqualTo(5 * DAY);
        assertThat(created.data().offenseLevel()).isEqualTo("habitual");
        assertThat(created.data().status()).isEqualTo("habitual");
    }

    @Test
    void aDurationInTheDataStructWithoutASeverityIsStillHonoured() {
        harness.lifecycle.createMinecraftPunishment(server, new MinecraftCreatePunishmentRequest(PLAYER_UUID.toString(),
            "Mod", null, CONFIGURED, "Reason", null, Map.of("duration", 5 * DAY), null, null, null, "medium", true));

        assertThat(harness.inserted().data().duration()).isEqualTo(5 * DAY);
        assertThat(harness.inserted().data().offenseLevel()).isEqualTo("medium");
    }

    @Test
    void theOldDetailPageSpellingInTheDataStructIsHonoured() {
        givenPriorOffenses(3);

        harness.lifecycle.createPanelPunishment(server, PLAYER_UUID, STAFF, new CreatePunishmentRequest("Staff", null, PunishmentIssuerKind.STAFF, SINGLE,
            null, null, null, null, null, Map.of("status", "first"), "Bad skin", null));

        assertThat(harness.inserted().data().offenseLevel()).isEqualTo("first");
        assertThat(harness.inserted().data().status()).isEqualTo("low");
        assertThat(harness.inserted().data().duration()).isEqualTo(PunishmentTypesFixture.SINGLE_FIRST);
    }

    @ParameterizedTest
    @CsvSource(value = {"31, regular, 14", "31, NULL, 14", "30, aggravated, 14", "30, NULL, 6"}, nullValues = "NULL")
    void everyMinecraftCreateRouteHonoursASelectedLevel(int ordinal, @Nullable String severity, long days) {
        harness.lifecycle.createMinecraftPunishment(server, new MinecraftCreatePunishmentRequest(
            PLAYER_UUID.toString(), "Mod", null, ordinal, "Reason", null, null, null, null, severity, "medium", true));

        Punishment created = harness.inserted();
        assertThat(created.data().offenseLevel()).isEqualTo("medium");
        assertThat(created.data().duration()).isEqualTo(days * DAY);
        assertThat(created.data().status()).isEqualTo(PunishmentStatus.UNSTARTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Unstarted", "Pardoned", "active", "severe"})
    void anythingOutsideTheAllowListLeavesHistoryInCharge(String notALevel) {
        givenPriorOffenses(1);

        Punishment created = createFromPanel(CONFIGURED, "severe", notALevel, null);

        assertThat(created.data().offenseLevel()).isEqualTo("medium");
        assertThat(created.data().status()).isEqualTo("medium");
    }

    @Test
    void aSelectedLevelCannotPutATimerOnAnIdentityRestriction() {
        Punishment created = createFromPanel(NAME_RESTRICTED, null, "medium", null);

        assertThat(created.data().duration()).isNull();
    }

    @Test
    void aSelectedLevelCannotPutATimerOnAManualBan() {
        Punishment created = createFromPanel(BAN, null, "medium", null);

        assertThat(created.data().duration()).isNull();
    }

    private void assertStoredMatches(PunishmentSeverityPreviewView preview, int ordinal, String severity, String level) {
        assertThat(createFromPanel(ordinal, severity, null, null).data().duration()).isEqualTo(preview.getDurationMs());
        assertThat(createFromPanel(ordinal, severity, level, null).data().duration()).isEqualTo(preview.getDurationMs());
    }

    private Punishment createFromPanel(int ordinal, @Nullable String severity, @Nullable String status, @Nullable Long duration) {
        harness.lifecycle.createPanelPunishment(server, PLAYER_UUID, STAFF, new CreatePunishmentRequest(
            "Staff", null, PunishmentIssuerKind.STAFF, ordinal, null, null, null, severity, status, null, "Rule broken", duration));
        return harness.inserted();
    }

    private void givenPriorOffenses(int count) {
        List<Punishment> history = IntStream.range(0, count)
            .mapToObj(index -> draft("PRIOR" + index, SINGLE, DAY, NOW.minus(Duration.ofDays(10)), null))
            .toList();
        when(harness.punishments.findByPlayer(PLAYER_ID)).thenReturn(history);
    }

    private static OffenseLevelDurations configuredDurations(PunishmentType type, String severity) {
        return type.isSingleSeverityPunishment()
            ? type.getSingleSeverityDurations()
            : type.getDurations().getForSeverity(severity);
    }

    private static long panelPreviewMillis(DurationDetail detail) {
        return detail.type().contains("permanent") ? PERMANENT : detail.value() * PANEL_UNIT_MILLIS.get(detail.unit());
    }
}
