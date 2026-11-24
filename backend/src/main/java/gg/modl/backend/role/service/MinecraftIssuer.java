package gg.modl.backend.role.service;

import gg.modl.backend.infrastructure.exception.ValidationException;
import org.jspecify.annotations.Nullable;

public final class MinecraftIssuer {
    public static final String ISSUER_REQUIRED = "MINECRAFT_ISSUER_REQUIRED";

    private static final MinecraftIssuer CONSOLE = new MinecraftIssuer(null);

    @Nullable
    private final String staffId;

    private MinecraftIssuer(@Nullable String staffId) {
        this.staffId = staffId;
    }

    public static MinecraftIssuer of(@Nullable String issuerId, @Nullable Boolean consoleMarker) {
        String named = issuerId == null || issuerId.isBlank() ? null : issuerId.trim();
        boolean console = Boolean.TRUE.equals(consoleMarker);
        if ((named != null) == console) {
            throw new ValidationException(
                "A Minecraft staff action must name exactly one issuer: a staff id, or the console marker", ISSUER_REQUIRED);
        }
        return named == null ? CONSOLE : new MinecraftIssuer(named);
    }

    public boolean isConsole() {
        return staffId == null;
    }

    @Nullable
    public String staffId() {
        return staffId;
    }
}
