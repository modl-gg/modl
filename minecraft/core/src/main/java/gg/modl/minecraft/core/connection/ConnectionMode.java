package gg.modl.minecraft.core.connection;

import org.jspecify.annotations.Nullable;

import java.util.Locale;

public enum ConnectionMode {
    EMBEDDED("embedded"),
    REMOTE("remote"),
    BEHIND_PROXY("behind-proxy");

    private final String yamlName;

    ConnectionMode(String yamlName) {
        this.yamlName = yamlName;
    }

    public String yamlName() {
        return yamlName;
    }

    public boolean runsCore() {
        return this == EMBEDDED || this == REMOTE;
    }

    public boolean isMember() {
        return this == BEHIND_PROXY;
    }

    public static @Nullable ConnectionMode fromYaml(@Nullable String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (ConnectionMode mode : values()) {
            if (mode.yamlName.equals(normalized)) return mode;
        }
        return null;
    }

    public static String allowedValues() {
        StringBuilder names = new StringBuilder();
        for (ConnectionMode mode : values()) {
            if (names.length() > 0) names.append(", ");
            names.append(mode.yamlName);
        }
        return names.toString();
    }
}
