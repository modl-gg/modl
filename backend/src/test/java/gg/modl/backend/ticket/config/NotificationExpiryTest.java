package gg.modl.backend.ticket.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class NotificationExpiryTest {
    @Test
    void lessThanOneDayIsRefused() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> NotificationExpiry.ofDays(0));

        assertEquals("Notifications must be kept at least 1 day", failure.getMessage());
    }

    @Test
    void theLifetimeIsTheConfiguredNumberOfDays() {
        assertEquals(Duration.ofDays(1), NotificationExpiry.ofDays(1).lifetime());
    }
}
