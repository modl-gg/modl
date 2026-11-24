package gg.modl.minecraft.core.packet;

import gg.modl.minecraft.core.util.SemanticVersion;
import org.jspecify.annotations.Nullable;

public final class PacketEventsVersion {
    public static final String MINIMUM = "2.14.0";

    private PacketEventsVersion() {
    }

    public static boolean isSupported(@Nullable String installedVersion) {
        return installedVersion != null
                && SemanticVersion.parse(releaseOf(installedVersion)).compareTo(SemanticVersion.parse(MINIMUM)) >= 0;
    }

    public static String unsupportedReason(@Nullable String installedVersion) {
        if (installedVersion == null) return "PacketEvents is not installed, but modl needs PacketEvents " + MINIMUM + " or newer";
        return "PacketEvents " + installedVersion + " is installed, but modl needs PacketEvents " + MINIMUM + " or newer";
    }

    private static String releaseOf(String version) {
        String trimmed = version.trim();
        int end = 0;
        while (end < trimmed.length() && (Character.isDigit(trimmed.charAt(end)) || trimmed.charAt(end) == '.')) end++;
        return trimmed.substring(0, end);
    }
}
