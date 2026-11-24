package gg.modl.backend.iplookup.service;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.iplookup.model.IpLookupProvider;
import gg.modl.backend.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class IpLookupBackoffTest {
    private static final Instant NOON = Instant.parse("2026-11-22T12:00:00Z");

    private final MutableClock clock = new MutableClock(NOON);
    private final IpLookupBackoff backoff = new IpLookupBackoff(clock);

    @Test
    void theDailyLimitIsAnnouncedEvenWhileAnEarlierFailurePauseIsActive() {
        backoff.pauseAfterFailure(IpLookupProvider.PROXYCHECK_KEYLESS);

        assertThat(backoff.pauseUntilNextDay(IpLookupProvider.PROXYCHECK_KEYLESS)).isTrue();
        assertThat(backoff.pauseUntilNextDay(IpLookupProvider.PROXYCHECK_KEYLESS)).isFalse();
    }

    @Test
    void aRefusalAfterAnExpiredPauseIsAnnouncedAgain() {
        assertThat(backoff.pauseAfterRefusal(IpLookupProvider.IPHUB)).isTrue();
        assertThat(backoff.pauseAfterRefusal(IpLookupProvider.IPHUB)).isFalse();

        clock.advance(IpLookupBackoff.AFTER_REFUSAL.plus(Duration.ofSeconds(1)));

        assertThat(backoff.allows(IpLookupProvider.IPHUB)).isTrue();
        assertThat(backoff.pauseAfterRefusal(IpLookupProvider.IPHUB)).isTrue();
    }

    @Test
    void aRefusalDuringAFailurePauseIsAnnounced() {
        backoff.pauseAfterFailure(IpLookupProvider.IPHUB);

        assertThat(backoff.pauseAfterRefusal(IpLookupProvider.IPHUB)).isTrue();
        assertThat(backoff.allows(IpLookupProvider.IPHUB)).isFalse();
    }

    @Test
    void theNextDayEndsTheDailyPause() {
        backoff.pauseUntilNextDay(IpLookupProvider.PROXYCHECK_KEYLESS);
        clock.advance(Duration.ofHours(12));

        assertThat(backoff.allows(IpLookupProvider.PROXYCHECK_KEYLESS)).isTrue();
        assertThat(backoff.pauseUntilNextDay(IpLookupProvider.PROXYCHECK_KEYLESS)).isTrue();
    }
}
