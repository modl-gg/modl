package gg.modl.backend.storage;

import java.util.Locale;
import java.util.Set;

public final class StorageKeySegments {
    private static final Set<String> WINDOWS_DEVICE_NAMES = Set.of(
        "CON", "PRN", "AUX", "NUL",
        "COM0", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT0", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    );

    private StorageKeySegments() {
    }

    public static boolean isWindowsDeviceName(String segment) {
        int extension = segment.indexOf('.');
        String baseName = extension >= 0 ? segment.substring(0, extension) : segment;
        return WINDOWS_DEVICE_NAMES.contains(baseName.toUpperCase(Locale.ROOT));
    }
}
