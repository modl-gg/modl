package gg.modl.backend.player.data.punishment;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

public enum PunishmentIssuerKind {
    STAFF, CONSOLE, AUTOMATED, UNKNOWN;

    private static final String CONSOLE_NAME = "console";

    public static PunishmentIssuerKind ofImported(@Nullable String issuerId, @Nullable String issuerName) {
        if (issuerId != null) {
            return STAFF;
        }
        if (issuerName == null || issuerName.isBlank()) {
            return UNKNOWN;
        }
        return issuerName.strip().toLowerCase(Locale.ROOT).equals(CONSOLE_NAME) ? CONSOLE : STAFF;
    }

    public static PunishmentIssuerKind fromStored(@Nullable String stored) {
        for (PunishmentIssuerKind kind : values()) {
            if (kind.name().equals(stored)) {
                return kind;
            }
        }
        return UNKNOWN;
    }
}
