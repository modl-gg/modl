package gg.modl.backend.player.data.punishment;

import gg.modl.backend.ticket.data.AppealWorkflowStatus;
import org.jspecify.annotations.Nullable;

public enum PunishmentModificationType {
    MANUAL_PARDON,
    APPEAL_ACCEPT,
    APPEAL_REJECT,
    APPEAL_DURATION_CHANGE,
    SYSTEM_PARDON,
    MANUAL_DURATION_CHANGE,
    ROLLBACK,
    SET_ALT_BLOCKING_TRUE,
    SET_ALT_BLOCKING_FALSE,
    SET_WIPING_TRUE,
    SET_WIPING_FALSE;

    @Nullable
    public PunishmentOptionChange optionChange() {
        return switch (this) {
            case SET_ALT_BLOCKING_TRUE -> new PunishmentOptionChange(PunishmentToggleOption.ALT_BLOCKING, true);
            case SET_ALT_BLOCKING_FALSE -> new PunishmentOptionChange(PunishmentToggleOption.ALT_BLOCKING, false);
            case SET_WIPING_TRUE -> new PunishmentOptionChange(PunishmentToggleOption.STAT_WIPE, true);
            case SET_WIPING_FALSE -> new PunishmentOptionChange(PunishmentToggleOption.STAT_WIPE, false);
            default -> null;
        };
    }

    @Nullable
    public static PunishmentOptionChange optionChange(String type) {
        PunishmentModificationType value = fromName(type);
        return value != null ? value.optionChange() : null;
    }

    public boolean isPardon() {
        return this == MANUAL_PARDON || this == APPEAL_ACCEPT || this == SYSTEM_PARDON;
    }

    public boolean endsEnforcement() {
        return isPardon() || this == ROLLBACK;
    }

    public boolean isDurationChange() {
        return this == MANUAL_DURATION_CHANGE || this == APPEAL_DURATION_CHANGE;
    }

    public static boolean isPardon(String type) {
        PunishmentModificationType value = fromName(type);
        return value != null && value.isPardon();
    }

    public static boolean endsEnforcement(String type) {
        PunishmentModificationType value = fromName(type);
        return value != null && value.endsEnforcement();
    }

    public static boolean isDurationChange(String type) {
        PunishmentModificationType value = fromName(type);
        return value != null && value.isDurationChange();
    }

    public @Nullable AppealWorkflowStatus appealOutcome() {
        return switch (this) {
            case APPEAL_ACCEPT, APPEAL_DURATION_CHANGE -> AppealWorkflowStatus.APPROVED;
            case APPEAL_REJECT -> AppealWorkflowStatus.REJECTED;
            default -> null;
        };
    }

    public static @Nullable PunishmentModificationType fromName(@Nullable String type) {
        if (type == null) {
            return null;
        }
        try {
            return valueOf(type);
        } catch (IllegalArgumentException invalidType) {
            return null;
        }
    }
}
