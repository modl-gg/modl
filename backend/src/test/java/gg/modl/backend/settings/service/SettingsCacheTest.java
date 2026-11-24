package gg.modl.backend.settings.service;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.support.MutableClock;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SettingsCacheTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final SettingsCache<String> cache = new SettingsCache<>(clock);

    @Test
    void aValueIsKeptUntilItsLifetimeEnds() {
        assertThat(cache.get(() -> "first")).isEqualTo("first");
        clock.advance(SettingsCache.LIFETIME.minusMillis(1));
        assertThat(cache.get(() -> "second")).isEqualTo("first");

        clock.advance(SettingsCache.LIFETIME);

        assertThat(cache.get(() -> "third")).isEqualTo("third");
    }

    @Test
    void invalidationForcesAReload() {
        cache.get(() -> "first");

        cache.invalidate();

        assertThat(cache.get(() -> "second")).isEqualTo("second");
    }

    @Test
    void aLoadThatStartedBeforeAnInvalidationIsNotCached() {
        String stale = cache.get(() -> {
            cache.invalidate();
            return "stale";
        });

        assertThat(stale).isEqualTo("stale");
        assertThat(cache.get(() -> "fresh")).isEqualTo("fresh");
    }
}
