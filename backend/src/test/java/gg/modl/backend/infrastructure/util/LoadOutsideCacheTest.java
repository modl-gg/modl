package gg.modl.backend.infrastructure.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LoadOutsideCacheTest {
    @Test
    void aValueLoadedWhileTheCacheWasInvalidatedIsReturnedButNotKept() {
        LoadOutsideCache<String, String> cache = new LoadOutsideCache<>(10, Duration.ofMinutes(2));
        AtomicInteger loads = new AtomicInteger();

        String stale = cache.get("role", () -> {
            loads.incrementAndGet();
            cache.invalidateAll();
            return "granted";
        });
        String fresh = cache.get("role", () -> {
            loads.incrementAndGet();
            return "revoked";
        });

        assertThat(stale).isEqualTo("granted");
        assertThat(fresh).isEqualTo("revoked");
        assertThat(cache.get("role", () -> "reloaded")).isEqualTo("revoked");
        assertThat(loads).hasValue(2);
    }

    @Test
    void aMissingValueIsNeverCached() {
        LoadOutsideCache<String, String> cache = new LoadOutsideCache<>(10, Duration.ofMinutes(2));

        assertThat(cache.get("key", () -> null)).isNull();
        assertThat(cache.get("key", () -> "value")).isEqualTo("value");
    }
}
