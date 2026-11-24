package gg.modl.backend.player.service;

import gg.modl.backend.player.data.punishment.PunishmentData;
import gg.modl.backend.player.dto.request.CreatePunishmentRequest;
import gg.modl.backend.settings.data.OffenderThresholdSettings;
import gg.modl.backend.settings.data.PunishmentCategory;
import gg.modl.backend.settings.data.PunishmentType;
import org.jspecify.annotations.Nullable;
import java.util.Locale;
import java.util.Map;

final class OffenseLevels {
    private static final String FIRST = "first";
    private static final String MEDIUM = "medium";
    private static final String HABITUAL = "habitual";
    private static final Map<String, String> LEVEL_BY_SELECTABLE_STATUS = Map.of(
        "low", FIRST,
        FIRST, FIRST,
        MEDIUM, MEDIUM,
        HABITUAL, HABITUAL);

    private OffenseLevels() {
    }

    @Nullable
    static String selectedIn(CreatePunishmentRequest request) {
        String selected = levelOf(request.status());
        if (selected != null || request.data() == null) {
            return selected;
        }
        return levelOf(request.data().get(PunishmentData.STATUS));
    }

    static boolean escalates(@Nullable PunishmentType type) {
        return type != null
            && !PunishmentCategory.isCoreOrdinal(type.getOrdinal())
            && !type.restrictsIdentity();
    }

    static String fromHistory(OffenderThresholdSettings thresholds, PlayerStatusCalculator.PlayerStatus status,
                              PunishmentType type) {
        boolean social = type.isSocial();
        return thresholds.getOffenseLevelInternal(social ? status.socialPoints() : status.gameplayPoints(), social);
    }

    @Nullable
    static String levelOf(@Nullable Object status) {
        return status instanceof String text ? LEVEL_BY_SELECTABLE_STATUS.get(text.toLowerCase(Locale.ROOT)) : null;
    }
}
