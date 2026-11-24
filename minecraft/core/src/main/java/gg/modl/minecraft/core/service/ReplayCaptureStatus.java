package gg.modl.minecraft.core.service;

import org.jspecify.annotations.Nullable;

import java.util.Locale;

public enum ReplayCaptureStatus {
    OK,
    NO_SERVER_CONNECTED,
    NO_ACTIVE_RECORDING,
    NOT_LOCAL,
    ERROR;

    public static @Nullable ReplayCaptureStatus fromWire(@Nullable String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }

        try {
            return ReplayCaptureStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return ERROR;
        }
    }
}
