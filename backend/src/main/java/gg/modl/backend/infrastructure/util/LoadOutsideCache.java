package gg.modl.backend.infrastructure.util;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public final class LoadOutsideCache<K, V> {
    private final Cache<K, V> cache;
    private final AtomicLong generation = new AtomicLong();

    public LoadOutsideCache(long maximumSize, Duration lifetime) {
        this.cache = Caffeine.newBuilder().maximumSize(maximumSize).expireAfterWrite(lifetime).build();
    }

    public V get(K key, Supplier<V> loader) {
        V cached = cache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        long loadedIn = generation.get();
        V loaded = loader.get();
        if (loaded != null && generation.get() == loadedIn) {
            cache.put(key, loaded);
            if (generation.get() != loadedIn) {
                cache.invalidate(key);
            }
        }
        return loaded;
    }

    public void invalidate(K key) {
        generation.incrementAndGet();
        cache.invalidate(key);
    }

    public void invalidateAll() {
        generation.incrementAndGet();
        cache.invalidateAll();
    }
}
