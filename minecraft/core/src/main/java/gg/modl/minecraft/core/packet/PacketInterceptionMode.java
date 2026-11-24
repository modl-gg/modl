package gg.modl.minecraft.core.packet;

import org.jspecify.annotations.Nullable;

import java.util.Locale;

public enum PacketInterceptionMode {
    AUTO,
    ENABLED,
    DISABLED;

    public static PacketInterceptionMode fromConfig(@Nullable Object value) {
        if (!(value instanceof String)) return AUTO;
        String normalized = ((String) value).trim().toUpperCase(Locale.ROOT);
        for (PacketInterceptionMode mode : values()) {
            if (mode.name().equals(normalized)) return mode;
        }
        return AUTO;
    }
}
