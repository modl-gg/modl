package gg.modl.backend.player.data.punishment;

import gg.modl.backend.settings.data.DurationDetail;
import gg.modl.backend.settings.data.PunishmentDurationResolver;
import gg.modl.backend.settings.data.PunishmentType;
import org.jspecify.annotations.Nullable;
import java.util.Locale;

public final class EnforcementCategoryResolver {
    private static final String DEFAULT_SEVERITY = "regular";
    private static final String FIRST_OFFENSE = "first";

    private EnforcementCategoryResolver() {
    }

    public static ResolvedCategory resolve(@Nullable PunishmentType type, PunishmentDataView data) {
        if (type == null || type.isKick()) {
            return ResolvedCategory.NONE;
        }
        if (type.restrictsIdentity()) {
            return IdentityRestriction.canLift(type, data) ? ResolvedCategory.BAN : ResolvedCategory.NONE;
        }
        if (type.isBan()) {
            return ResolvedCategory.BAN;
        }
        if (type.isMute()) {
            return ResolvedCategory.MUTE;
        }
        if (data.asMap() == null) {
            return ResolvedCategory.NONE;
        }
        String severity = data.severity() != null ? data.severity() : DEFAULT_SEVERITY;
        DurationDetail detail = PunishmentDurationResolver.resolveDetail(type, severity, offenseLevel(data));
        if (detail == null) {
            return ResolvedCategory.NONE;
        }
        if (detail.isBan()) {
            return ResolvedCategory.BAN;
        }
        return detail.isMute() ? ResolvedCategory.MUTE : ResolvedCategory.NONE;
    }

    private static String offenseLevel(PunishmentDataView data) {
        String offenseLevel = data.offenseLevel();
        if (offenseLevel != null) {
            return offenseLevel;
        }
        String status = data.status();
        if (status == null || PunishmentStatus.UNSTARTED.equals(status) || PunishmentStatus.PARDONED.equals(status)) {
            return FIRST_OFFENSE;
        }
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "medium" -> "medium";
            case "habitual" -> "habitual";
            default -> FIRST_OFFENSE;
        };
    }
}
