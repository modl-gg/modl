package gg.modl.backend.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.email.ValidityPeriod;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class LoginLockoutNotice {
    static final String MESSAGE = "Codes are not sent to {} for the next {}: too many incorrect codes were entered for this address.";
    private static final int MAX_TRACKED_LOCKOUTS = 1_000;

    private final OperatorNotice operatorNotice;
    private final Cache<Lockout, Boolean> announcedLockouts;

    public LoginLockoutNotice(OperatorNotice operatorNotice, AuthConfiguration authConfiguration) {
        this.operatorNotice = operatorNotice;
        this.announcedLockouts = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(authConfiguration.getEmailCodeFailureWindowSeconds()))
            .maximumSize(MAX_TRACKED_LOCKOUTS)
            .build();
    }

    public void announce(String normalizedEmail, Instant lockedUntil, Instant now) {
        if (announcedLockouts.asMap().putIfAbsent(new Lockout(normalizedEmail, lockedUntil), Boolean.TRUE) == null) {
            operatorNotice.publish(MESSAGE, EmailAddressUtil.mask(normalizedEmail),
                new ValidityPeriod(roundedUp(Duration.between(now, lockedUntil))).describe());
        }
    }

    private static Duration roundedUp(Duration remaining) {
        Duration unit = remaining.compareTo(Duration.ofHours(1)) > 0 ? Duration.ofHours(1) : Duration.ofMinutes(1);
        long units = Math.ceilDiv(remaining.toSeconds(), unit.toSeconds());
        return unit.multipliedBy(Math.max(1, units));
    }

    private record Lockout(String normalizedEmail, Instant lockedUntil) {
    }
}
