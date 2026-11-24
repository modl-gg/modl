package gg.modl.backend.player.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.settings.data.DurationDetail;
import gg.modl.backend.settings.data.OffenderThresholdSettings;
import gg.modl.backend.settings.data.OffenseLevelDurations;
import gg.modl.backend.settings.data.PunishmentDurations;
import gg.modl.backend.settings.data.PunishmentPoints;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.service.OffenderThresholdSettingsService;
import gg.modl.backend.settings.service.PunishmentTypeService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class PunishmentTypesFixture {
    static final int CONFIGURED = 30;
    static final int SINGLE = 31;
    static final int NAME_RESTRICTED = 32;
    static final int DELETABLE = 33;
    static final int ZERO_LENGTH = 34;
    static final int SKIN_RESTRICTED = 35;
    static final long LENIENT_FIRST = 24L * 3_600_000L;
    static final long SEVERE_FIRST = 7L * 24L * 3_600_000L;
    static final long SINGLE_FIRST = 3L * 24L * 3_600_000L;
    static final long SINGLE_MEDIUM = 14L * 24L * 3_600_000L;

    private final List<PunishmentType> live = new ArrayList<>();
    private final List<Integer> deleted = new ArrayList<>();
    private final PunishmentTypeService types = mock(PunishmentTypeService.class);
    private final OffenderThresholdSettingsService thresholds = mock(OffenderThresholdSettingsService.class);

    PunishmentTypesFixture() {
        live.add(core(0, "Kick"));
        live.add(core(1, "Manual Mute"));
        live.add(PunishmentType.builder().ordinal(2).name("Manual Ban").category("Administrative")
            .canBeAltBlocking(true).canBeStatWiping(true).build());
        live.add(core(4, "Linked Ban"));
        live.add(configured(CONFIGURED, "Cheating").build());
        live.add(configured(DELETABLE, "Griefing").build());
        live.add(configured(ZERO_LENGTH, "Scamming")
            .durations(new PunishmentDurations(banDurations(0), banDurations(0), banDurations(0)))
            .build());
        live.add(PunishmentType.builder()
            .ordinal(SINGLE)
            .name("Bad Skin")
            .category("Gameplay")
            .singleSeverityPunishment(true)
            .singleSeverityPoints(5)
            .singleSeverityDurations(new OffenseLevelDurations(
                new DurationDetail(3, "days", "ban"),
                new DurationDetail(14, "days", "ban"),
                new DurationDetail(1, "months", "permanent ban")))
            .build());
        live.add(PunishmentType.builder()
            .ordinal(NAME_RESTRICTED)
            .name("Bad Name")
            .category("Administrative Ban")
            .permanentUntilUsernameChange(true)
            .build());
        live.add(PunishmentType.builder()
            .ordinal(SKIN_RESTRICTED)
            .name("Indecent Skin")
            .category("Social")
            .permanentUntilSkinChange(true)
            .build());

        when(types.getPunishmentTypes(any())).thenAnswer(call -> List.copyOf(live));
        when(types.getPunishmentTypeByOrdinal(any(), anyInt())).thenAnswer(call -> find(call.getArgument(1)));
        when(types.getPunishmentTypeName(any(), anyInt())).thenAnswer(call -> nameOf(call.getArgument(1)));
        when(thresholds.getThresholdSettings(any())).thenReturn(OffenderThresholdSettings.builder()
            .gameplay(new OffenderThresholdSettings.CategoryThresholds(4, 12, 6))
            .social(new OffenderThresholdSettings.CategoryThresholds(4, 8, 6))
            .build());
    }

    PunishmentTypeService types() {
        return types;
    }

    OffenderThresholdSettingsService thresholds() {
        return thresholds;
    }

    void delete(int ordinal) {
        live.removeIf(type -> type.getOrdinal() == ordinal);
        deleted.add(ordinal);
    }

    private Optional<PunishmentType> find(int ordinal) {
        return live.stream().filter(type -> type.getOrdinal() == ordinal).findFirst();
    }

    private String nameOf(int ordinal) {
        return find(ordinal).map(PunishmentType::getName)
            .orElse(deleted.contains(ordinal) ? "Griefing (deleted)" : "Unknown");
    }

    private static PunishmentType core(int ordinal, String name) {
        return PunishmentType.builder().ordinal(ordinal).name(name).category("Administrative").build();
    }

    private static PunishmentType.PunishmentTypeBuilder configured(int ordinal, String name) {
        return PunishmentType.builder()
            .ordinal(ordinal)
            .name(name)
            .category("Gameplay")
            .points(new PunishmentPoints(4, 6, 10))
            .durations(new PunishmentDurations(banDurations(1), banDurations(3), banDurations(7)));
    }

    private static OffenseLevelDurations banDurations(int days) {
        return new OffenseLevelDurations(
            new DurationDetail(days, "days", "ban"),
            new DurationDetail(days * 2, "days", "ban"),
            new DurationDetail(days * 4, "days", "ban"));
    }
}
