package gg.modl.backend.player.service;

import gg.modl.backend.player.data.punishment.PunishmentIssuerKind;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public record IssuerAttribution(PunishmentIssuerKind kind, String name) {
    private static final IssuerAttribution CONSOLE = new IssuerAttribution(PunishmentIssuerKind.CONSOLE, "");
    private static final IssuerAttribution UNKNOWN = new IssuerAttribution(PunishmentIssuerKind.UNKNOWN, "");

    public static IssuerAttribution of(PunishmentIssuerKind kind, @Nullable String issuerId, @Nullable String issuerName,
                                       Map<String, String> staffNamesById) {
        return switch (kind) {
            case STAFF -> staff(issuerId == null ? null : staffNamesById.get(issuerId), issuerName);
            case CONSOLE -> CONSOLE;
            case AUTOMATED -> new IssuerAttribution(PunishmentIssuerKind.AUTOMATED, stripped(issuerName));
            case UNKNOWN -> UNKNOWN;
        };
    }

    public String key() {
        return kind + ":" + name.toLowerCase(Locale.ROOT);
    }

    public boolean isStaffNamed(String username) {
        return kind == PunishmentIssuerKind.STAFF && name.equalsIgnoreCase(username);
    }

    private static IssuerAttribution staff(@Nullable String resolvedName, @Nullable String storedName) {
        String name = resolvedName != null ? resolvedName : stripped(storedName);
        return name.isEmpty() ? UNKNOWN : new IssuerAttribution(PunishmentIssuerKind.STAFF, name);
    }

    private static String stripped(@Nullable String name) {
        return name == null ? "" : name.strip();
    }
}
