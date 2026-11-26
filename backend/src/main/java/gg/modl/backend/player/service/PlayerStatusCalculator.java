package gg.modl.backend.player.service;

import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentDataView;
import gg.modl.backend.player.data.punishment.PunishmentState;
import gg.modl.backend.player.data.punishment.ResolvedCategory;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.OffenderThresholdSettings;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.service.OffenderThresholdSettingsService;
import gg.modl.backend.settings.service.PunishmentTypeIndex;
import gg.modl.backend.settings.service.PunishmentTypeService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PlayerStatusCalculator {
    private final PunishmentTypeService punishmentTypeService;
    private final OffenderThresholdSettingsService offenderThresholdSettingsService;
    private final Clock clock;

    public PlayerStatus calculateStatus(Server server, List<Punishment> punishments) {
        List<PunishmentType> types = punishmentTypeService.getPunishmentTypes(server);
        Map<Integer, PunishmentType> typesByOrdinal = PunishmentTypeIndex.byOrdinal(types);
        OffenderThresholdSettings thresholdSettings = offenderThresholdSettingsService.getThresholdSettings(server);
        long socialExpiryMs = thresholdSettings.getSocial().getPointExpiryMs();
        long gameplayExpiryMs = thresholdSettings.getGameplay().getPointExpiryMs();
        long now = clock.millis();

        int socialPoints = 0;
        int gameplayPoints = 0;

        for (Punishment punishment : punishments) {
            if (!isPunishmentEligible(punishment)) {
                continue;
            }

            int typeOrdinal = punishment.getTypeOrdinal();
            String severity = punishment.data().severity();

            PunishmentType type = typesByOrdinal.get(typeOrdinal);
            if (type == null) {
                continue;
            }

            Instant effectiveExpiry = getEffectiveExpiry(punishment);
            if (effectiveExpiry != null) {
                long expiryMs = type.isSocial() ? socialExpiryMs : gameplayExpiryMs;
                if (effectiveExpiry.toEpochMilli() + expiryMs < now) {
                    continue;
                }
            }

            int points = type.getPointsForSeverity(severity != null ? severity : "regular");

            if (type.isSocial()) {
                socialPoints += points;
            } else if (type.isGameplay()) {
                gameplayPoints += points;
            }
        }

        String socialStatus = thresholdSettings.getSocialOffenderLevel(socialPoints);
        String gameplayStatus = thresholdSettings.getGameplayOffenderLevel(gameplayPoints);

        return new PlayerStatus(socialStatus, gameplayStatus, socialPoints, gameplayPoints);
    }

    public boolean isPunishmentActive(Punishment punishment) {
        if (!isPunishmentEligible(punishment)) {
            return false;
        }

        Instant effectiveExpiry = getEffectiveExpiry(punishment);
        return effectiveExpiry == null || !effectiveExpiry.isBefore(now());
    }

    public boolean isPunishmentActiveOrUnstarted(Punishment punishment) {
        return isPunishmentActive(punishment) || punishment.isUnstarted();
    }

    private boolean isPunishmentEligible(Punishment punishment) {
        if (punishment.getTypeOrdinal() == Punishment.KICK_TYPE_ORDINAL) {
            return false;
        }

        PunishmentDataView data = punishment.data();
        if (data.asMap() == null) {
            return false;
        }

        if (data.isUnstarted()) {
            return false;
        }

        return !punishment.hasEndedEnforcement();
    }


    public @Nullable Instant getEffectiveExpiry(Punishment punishment) {
        return PunishmentState.displayExpiry(punishment, now());
    }

    public ResolvedCategory getEffectiveCategory(Punishment punishment) {
        return punishment.enforcementCategory();
    }

    public boolean isPunishmentNaturallyExpired(Punishment punishment) {
        if (punishment.getStarted() == null) {
            return false;
        }

        if (punishment.getTypeOrdinal() == Punishment.KICK_TYPE_ORDINAL) {
            return false;
        }

        if (punishment.hasEndedEnforcement()) {
            return false;
        }

        Instant effectiveExpiry = getEffectiveExpiry(punishment);
        if (effectiveExpiry == null) {
            return false;
        }

        return effectiveExpiry.isBefore(now());
    }

    public boolean isStatWipeDue(Punishment punishment) {
        PunishmentDataView data = punishment.data();
        return data.wipeAfterExpiry() && !data.statWipeCompleted() && isPunishmentNaturallyExpired(punishment);
    }

    private Instant now() {
        return Instant.ofEpochMilli(clock.millis());
    }

    public record PlayerStatus(
        String social,
        String gameplay,
        int socialPoints,
        int gameplayPoints
    ) {
    }
}
