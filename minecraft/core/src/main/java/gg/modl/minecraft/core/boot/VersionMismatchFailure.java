package gg.modl.minecraft.core.boot;

import gg.modl.update.ReleaseVersion;
import gg.modl.update.VersionCompatibility;
import org.jspecify.annotations.Nullable;

import java.io.IOException;

public final class VersionMismatchFailure extends IOException {
    private static final long serialVersionUID = 1L;

    private final transient VersionCompatibility compatibility;
    private final String pluginVersion;
    private final String serverVersion;
    private final String serverUrl;
    private final String reason;

    public VersionMismatchFailure(VersionCompatibility compatibility, String pluginVersion, @Nullable String serverVersion,
                                  String serverUrl) {
        this(compatibility, pluginVersion, serverVersion, serverUrl, "This plugin is modl " + pluginVersion
                + ", but the modl server at " + serverUrl + " runs "
                + (serverVersion == null || serverVersion.isEmpty() ? "an unknown version" : serverVersion) + ".");
    }

    private VersionMismatchFailure(VersionCompatibility compatibility, String pluginVersion,
                                   @Nullable String serverVersion, String serverUrl, String reason) {
        super(reason);
        this.reason = reason;
        this.compatibility = compatibility;
        this.pluginVersion = pluginVersion;
        this.serverVersion = serverVersion == null ? "" : serverVersion;
        this.serverUrl = serverUrl;
    }

    public VersionCompatibility compatibility() {
        return compatibility;
    }

    public String serverVersion() {
        return serverVersion;
    }

    public String reason() {
        return reason;
    }

    public String remedy() {
        switch (compatibility) {
            case PLUGIN_OLDER:
                return "Update the modl plugin to " + lineOf(serverVersion) + ".x on this server and restart it.";
            case SERVER_OLDER:
                return "Update the modl server to " + lineOf(pluginVersion) + ".x. modl reconnects on its own afterwards.";
            default:
                return "Run the same MAJOR.MINOR version of modl on both sides.";
        }
    }

    public boolean sameAs(@Nullable VersionMismatchFailure other) {
        return other != null && compatibility == other.compatibility && serverVersion.equals(other.serverVersion)
                && serverUrl.equals(other.serverUrl);
    }

    private static String lineOf(String version) {
        ReleaseVersion parsed = ReleaseVersion.tryParse(version);
        return parsed == null ? version : parsed.line();
    }
}
