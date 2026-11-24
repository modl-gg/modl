package gg.modl.backend.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import gg.modl.backend.infrastructure.logging.OperatorNotice;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoginLockoutNoticeTest {
    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");

    private OperatorNotice operatorNotice;
    private LoginLockoutNotice notice;

    @BeforeEach
    void setUp() {
        operatorNotice = mock(OperatorNotice.class);
        AuthConfiguration configuration = new AuthConfiguration();
        configuration.setEmailCodeFailureWindowSeconds(Duration.ofHours(24).toSeconds());
        notice = new LoginLockoutNotice(operatorNotice, configuration);
    }

    @Test
    void announcesAMaskedAddressWithTheRemainingTimeRoundedUp() {
        notice.announce("mod@example.com", NOW.plus(Duration.ofMinutes(90)), NOW);

        verify(operatorNotice).publish(LoginLockoutNotice.MESSAGE, "m***@example.com", "2 hours");
    }

    @Test
    void announcesEachLockoutOnce() {
        Instant lockedUntil = NOW.plus(Duration.ofMinutes(5));
        notice.announce("mod@example.com", lockedUntil, NOW);
        notice.announce("mod@example.com", lockedUntil, NOW.plus(Duration.ofMinutes(1)));
        notice.announce("admin@example.com", NOW.plus(Duration.ofSeconds(30)), NOW);
        notice.announce("mod@example.com", NOW.plus(Duration.ofHours(3)), NOW);

        verify(operatorNotice).publish(LoginLockoutNotice.MESSAGE, "m***@example.com", "5 minutes");
        verify(operatorNotice).publish(LoginLockoutNotice.MESSAGE, "a***@example.com", "1 minute");
        verify(operatorNotice).publish(LoginLockoutNotice.MESSAGE, "m***@example.com", "3 hours");
        verify(operatorNotice, times(3)).publish(anyString(), any(), any());
    }
}
