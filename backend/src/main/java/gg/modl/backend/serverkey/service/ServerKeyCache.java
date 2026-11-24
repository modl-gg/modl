package gg.modl.backend.serverkey.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import gg.modl.backend.serverkey.ServerKey;
import gg.modl.backend.serverkey.ServerKeyChangedEvent;
import gg.modl.backend.serverkey.ServerKeyRevokedEvent;
import gg.modl.backend.serverkey.repository.ServerKeyRepository;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
public class ServerKeyCache {
    static final Duration FOUND_LIFETIME = Duration.ofSeconds(30);
    static final Duration MISSING_LIFETIME = Duration.ofSeconds(5);
    static final int MAXIMUM_ENTRIES = 512;

    private final ServerKeyRepository repository;
    private final Cache<String, Optional<ServerKey>> byHash = newCache();
    private final Cache<String, Optional<ServerKey>> byId = newCache();
    private final AtomicLong generation = new AtomicLong();

    public ServerKeyCache(ServerKeyRepository repository) {
        this.repository = repository;
    }

    public Optional<ServerKey> findByHash(String keyHash) {
        return lookup(byHash, keyHash, () -> repository.findByKeyHash(keyHash));
    }

    public Optional<ServerKey> findById(String keyId) {
        return lookup(byId, keyId, () -> repository.findById(keyId));
    }

    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void onChanged(ServerKeyChangedEvent event) {
        forget(event.key());
    }

    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void onRevoked(ServerKeyRevokedEvent event) {
        forget(event.key());
    }

    private void forget(ServerKey key) {
        generation.incrementAndGet();
        byHash.invalidate(key.keyHash());
        byId.invalidate(key.id());
    }

    private Optional<ServerKey> lookup(Cache<String, Optional<ServerKey>> cache, String key, Supplier<Optional<ServerKey>> loader) {
        Optional<ServerKey> cached = cache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        long loadedIn = generation.get();
        Optional<ServerKey> loaded = loader.get();
        if (generation.get() == loadedIn) {
            cache.put(key, loaded);
            if (generation.get() != loadedIn) {
                cache.invalidate(key);
            }
        }
        return loaded;
    }

    private static Cache<String, Optional<ServerKey>> newCache() {
        return Caffeine.newBuilder()
            .maximumSize(MAXIMUM_ENTRIES)
            .expireAfter(new FoundOrMissingExpiry())
            .build();
    }

    private static final class FoundOrMissingExpiry implements Expiry<String, Optional<ServerKey>> {
        @Override
        public long expireAfterCreate(String key, Optional<ServerKey> value, long currentTime) {
            return (value.isPresent() ? FOUND_LIFETIME : MISSING_LIFETIME).toNanos();
        }

        @Override
        public long expireAfterUpdate(String key, Optional<ServerKey> value, long currentTime, long currentDuration) {
            return expireAfterCreate(key, value, currentTime);
        }

        @Override
        public long expireAfterRead(String key, Optional<ServerKey> value, long currentTime, long currentDuration) {
            return currentDuration;
        }
    }
}
