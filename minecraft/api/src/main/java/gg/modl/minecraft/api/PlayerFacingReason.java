package gg.modl.minecraft.api;

import org.jspecify.annotations.Nullable;

final class PlayerFacingReason {
    private PlayerFacingReason() {
    }

    static @Nullable String of(int ordinal, @Nullable String issuedReason, @Nullable String typeDescription) {
        if (isWrittenForThePlayer(ordinal) && hasText(issuedReason)) return issuedReason;
        return hasText(typeDescription) ? typeDescription : null;
    }

    private static boolean isWrittenForThePlayer(int ordinal) {
        return ordinal == PunishmentTypeClassifier.ORDINAL_KICK
                || ordinal == PunishmentTypeClassifier.ORDINAL_MUTE
                || ordinal == PunishmentTypeClassifier.ORDINAL_BAN;
    }

    private static boolean hasText(@Nullable String text) {
        return text != null && !text.trim().isEmpty();
    }
}
