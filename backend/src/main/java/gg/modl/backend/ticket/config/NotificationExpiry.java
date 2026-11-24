package gg.modl.backend.ticket.config;

import java.time.Duration;

public interface NotificationExpiry {
    Duration lifetime();

    static NotificationExpiry ofDays(int days) {
        if (days < 1) {
            throw new IllegalArgumentException("Notifications must be kept at least 1 day");
        }
        Duration lifetime = Duration.ofDays(days);
        return () -> lifetime;
    }
}
