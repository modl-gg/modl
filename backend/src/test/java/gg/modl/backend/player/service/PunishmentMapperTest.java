package gg.modl.backend.player.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import gg.modl.backend.player.data.punishment.PunishmentIssuerKind;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentNote;
import gg.modl.backend.player.dto.response.SimplePunishmentView;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.service.OffenderThresholdSettingsService;
import gg.modl.backend.settings.service.PunishmentTypeService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PunishmentMapperTest {

    private PlayerStatusCalculator statusCalculator;
    private List<PunishmentType> types;

    @BeforeEach
    void setUp() {
        statusCalculator = new PlayerStatusCalculator(
            mock(PunishmentTypeService.class),
            mock(OffenderThresholdSettingsService.class),
            Clock.systemUTC()
        );
        types = List.of(PunishmentType.builder()
            .ordinal(6)
            .name("Chat Abuse")
            .category("Social")
            .build());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void kicksManualMutesAndManualBansShowTheTypedReasonToThePlayer(int typeOrdinal) {
        assertEquals("spam in chat", descriptionShownToThePlayer(typeOrdinal, "spam in chat"));
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4, 5, 6, 40})
    void everyOtherTypeShowsItsPlayerDescriptionInsteadOfTheInternalReason(int typeOrdinal) {
        assertEquals("Watch your language", descriptionShownToThePlayer(typeOrdinal, "reported by Alex"));
    }

    @Test
    void aTypeWithoutPlayerDescriptionNeverShowsTheInternalReason() {
        Map<String, Object> data = new HashMap<>();
        data.put("reason", "reported by Alex");
        Punishment punishment = punishment(6, List.of(autoNote()), data);

        SimplePunishmentView simple = PunishmentMapper.toSimplePunishment(punishment, types, statusCalculator, Map.of());

        assertEquals("No reason specified", simple.description());
    }

    @Test
    void staffViewsKeepTheInternalReason() {
        Map<String, Object> data = new HashMap<>();
        data.put("reason", "reported by Alex");
        Punishment punishment = punishment(6, List.of(autoNote()), data);

        assertEquals("reported by Alex", PunishmentMapper.toPunishmentView(punishment, types).data().get("reason"));
    }

    private String descriptionShownToThePlayer(int typeOrdinal, String reason) {
        Map<String, Object> data = new HashMap<>();
        data.put("reason", reason);
        List<PunishmentType> described = List.of(PunishmentType.builder()
            .ordinal(typeOrdinal)
            .name("Described")
            .category("Social")
            .playerDescription("Watch your language")
            .build());
        Punishment punishment = punishment(typeOrdinal, List.of(autoNote()), data);

        return PunishmentMapper.toSimplePunishment(punishment, described, statusCalculator, Map.of()).description();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
    void aStaffNoteNeverBecomesTheDescriptionThePlayerSees(int typeOrdinal) {
        Map<String, Object> data = new HashMap<>();
        data.put("reason", " ");
        Punishment punishment = punishment(typeOrdinal, List.of(note("informant is Alex"), autoNote()), data);

        SimplePunishmentView simple = PunishmentMapper.toSimplePunishment(punishment, types, statusCalculator, Map.of());

        assertEquals("No reason specified", simple.description());
    }

    @Test
    void customTypeWithOnlyAutoNoteAndNoReasonGetsDefault() {
        Map<String, Object> data = new HashMap<>();
        data.put("duration", 3600_000L);
        Punishment punishment = punishment(6, List.of(autoNote()), data);

        SimplePunishmentView simple = PunishmentMapper.toSimplePunishment(punishment, types, statusCalculator, Map.of());

        assertEquals("No reason specified", simple.description());
    }

    private PunishmentNote autoNote() {
        return note("issued 1d mute");
    }

    private PunishmentNote note(String text) {
        return new PunishmentNote("note-" + text.hashCode(), text, Instant.now(), "Mod", null);
    }

    private Punishment punishment(int typeOrdinal, List<PunishmentNote> notes, Map<String, Object> data) {
        return new Punishment(
            "p-1",
            typeOrdinal,
            "Mod",
            null,
            PunishmentIssuerKind.STAFF,
            Instant.now(),
            Instant.now(),
            new ArrayList<>(),
            new ArrayList<>(notes),
            new ArrayList<>(),
            new ArrayList<>(),
            data
        );
    }
}
