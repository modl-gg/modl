package gg.modl.update;

import org.jspecify.annotations.Nullable;

public enum VersionCompatibility {
    COMPATIBLE,
    PLUGIN_OLDER,
    SERVER_OLDER,
    UNKNOWN;

    public static VersionCompatibility between(@Nullable String pluginVersion, @Nullable String serverVersion) {
        return between(ReleaseVersion.tryParse(pluginVersion), ReleaseVersion.tryParse(serverVersion));
    }

    public static VersionCompatibility between(@Nullable ReleaseVersion plugin, @Nullable ReleaseVersion server) {
        if (plugin == null || server == null) {
            return UNKNOWN;
        }
        if (plugin.sameLine(server)) {
            return COMPATIBLE;
        }
        return plugin.isOlderThan(server) ? PLUGIN_OLDER : SERVER_OLDER;
    }

    public static VersionCompatibility fromHeader(@Nullable String value) {
        if (value != null) {
            for (VersionCompatibility compatibility : values()) {
                if (compatibility != COMPATIBLE && compatibility.name().equals(value)) {
                    return compatibility;
                }
            }
        }
        return UNKNOWN;
    }

    public boolean isCompatible() {
        return this == COMPATIBLE;
    }
}
