package gg.modl.backend.hostedimport.mapping;

import gg.modl.backend.hostedimport.archive.ArchiveDocument;
import org.jspecify.annotations.Nullable;
import java.time.Instant;

public final class LogTimes {
    private LogTimes() {
    }

    public static Instant sentAt(@Nullable Object timestamp, Instant fallback, Instant latest) {
        Long millis = ArchiveDocument.longOf(timestamp);
        Instant instant = millis != null && millis >= 0 ? ArchiveDocument.instantOf(millis) : null;
        return clamp(instant, fallback, latest);
    }

    public static Instant clamp(@Nullable Instant value, Instant fallback, Instant latest) {
        if (value == null || value.isBefore(Instant.EPOCH) || value.isAfter(latest)) {
            return fallback;
        }
        return value;
    }
}
