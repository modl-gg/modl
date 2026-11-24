package gg.modl.backend.status.facts;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

final class StatusFormats {
    private StatusFormats() {
    }

    static String duration(Duration duration) {
        long days = duration.toDays();
        long hours = duration.toHoursPart();
        long minutes = duration.toMinutesPart();
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        return hours > 0 ? hours + "h " + minutes + "m" : minutes + "m";
    }

    static String instant(Instant instant) {
        return instant.truncatedTo(ChronoUnit.SECONDS).toString();
    }

    static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }
}
