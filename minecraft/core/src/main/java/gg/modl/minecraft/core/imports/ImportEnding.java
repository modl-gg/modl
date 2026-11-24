package gg.modl.minecraft.core.imports;

import java.util.Locale;

enum ImportEnding {
    CANCELLED,
    TIMED_OUT,
    INTERRUPTED,
    EXPORT_FAILED,
    UPLOAD_FAILED,
    FILE_TOO_LARGE,
    INVALID_FILE,
    IMPORT_FAILED;

    private static final String WIRE_PREFIX = "IMPORT_END_REASON_";

    String wireName() {
        return WIRE_PREFIX + name();
    }

    String messageKey() {
        return "import.ended." + name().toLowerCase(Locale.ROOT);
    }

    static ImportEnding fromWire(String wireName) {
        for (ImportEnding ending : values()) {
            if (ending.wireName().equals(wireName)) return ending;
        }
        return IMPORT_FAILED;
    }
}
