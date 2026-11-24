package gg.modl.backend.player.service;

import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.DurationDetail;
import gg.modl.backend.settings.data.OffenderThresholdSettings;
import gg.modl.backend.settings.data.PunishmentDurationResolver;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.data.SeverityLevel;
import gg.modl.backend.settings.service.OffenderThresholdSettingsService;
import gg.modl.backend.settings.service.PunishmentTypeService;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PunishmentDurationCalculator {

    private final PunishmentTypeService punishmentTypeService;
    private final OffenderThresholdSettingsService thresholdSettingsService;
    private final PlayerStatusCalculator statusCalculator;

    public record DurationResult(@Nullable Long duration, @Nullable String status, @Nullable String offenseLevel) {}

    public DurationResult calculate(Server server, List<Punishment> existingPunishments, int typeOrdinal, @Nullable String severity,
                                    @Nullable String selectedOffenseLevel) {
        List<PunishmentType> types = punishmentTypeService.getPunishmentTypes(server);
        PunishmentType punishmentType = types.stream()
            .filter(t -> Objects.equals(t.getOrdinal(), typeOrdinal))
            .findFirst()
            .orElse(null);

        if (punishmentType == null) {
            return new DurationResult(null, null, null);
        }

        String offenseLevel = selectedOffenseLevel != null
            ? selectedOffenseLevel
            : offenseLevelFromHistory(server, punishmentType, existingPunishments);

        String internalSeverity = SeverityLevel.normalize(severity);

        DurationDetail durationDetail = PunishmentDurationResolver.resolveDetail(punishmentType, internalSeverity, offenseLevel);

        String displayStatus = PunishmentMapper.offenseDisplayStatus(offenseLevel);

        Long calculatedDuration = null;
        if (durationDetail != null) {
            long durationMs = durationDetail.toMilliseconds();
            if (durationMs != 0) {
                calculatedDuration = durationMs;
            }
        }

        return new DurationResult(calculatedDuration, displayStatus, offenseLevel);
    }

    private String offenseLevelFromHistory(Server server, PunishmentType type, List<Punishment> existingPunishments) {
        OffenderThresholdSettings thresholds = thresholdSettingsService.getThresholdSettings(server);
        PlayerStatusCalculator.PlayerStatus currentStatus = statusCalculator.calculateStatus(server, existingPunishments);
        return OffenseLevels.fromHistory(thresholds, currentStatus, type);
    }
}
