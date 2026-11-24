package gg.modl.backend.update.compat;

import gg.modl.update.CompatibilityHeaders;
import gg.modl.update.ReleaseVersion;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import java.util.Locale;
import java.util.regex.Pattern;

public record PluginIdentity(String pluginVersion, @Nullable String bootId, String platform) {
    private static final Pattern BOOT_ID = Pattern.compile("[0-9a-fA-F-]{8,36}");
    private static final Pattern PLATFORM = Pattern.compile("[A-Z_]{1,16}");
    private static final Pattern UNPRINTABLE = Pattern.compile("[^0-9A-Za-z.+_-]");
    private static final String UNKNOWN_PLATFORM = "UNKNOWN";

    public static PluginIdentity of(String pluginVersion, @Nullable String bootId, @Nullable String platform) {
        String cleanBootId = bootId != null && BOOT_ID.matcher(bootId).matches() ? bootId.toLowerCase(Locale.ROOT) : null;
        String normalizedPlatform = platform == null ? "" : platform.trim().toUpperCase(Locale.ROOT);
        return new PluginIdentity(printable(pluginVersion), cleanBootId,
            PLATFORM.matcher(normalizedPlatform).matches() ? normalizedPlatform : UNKNOWN_PLATFORM);
    }

    private static String printable(String version) {
        String bounded = version.length() > ReleaseVersion.MAX_LENGTH ? version.substring(0, ReleaseVersion.MAX_LENGTH) : version;
        return UNPRINTABLE.matcher(bounded).replaceAll("?");
    }

    @Nullable
    public static PluginIdentity fromHeaders(HttpServletRequest request) {
        String version = request.getHeader(CompatibilityHeaders.PLUGIN_VERSION);
        if (version == null) {
            return null;
        }
        return of(version, request.getHeader(CompatibilityHeaders.BOOT_ID),
            request.getHeader(CompatibilityHeaders.PLATFORM));
    }
}
