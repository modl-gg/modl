package gg.modl.backend.player.external;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CrafatarProxyService {
    static final int MAX_CONCURRENT_FETCHES = 8;
    private static final int MIN_AVATAR_SIZE = 8;
    private static final int MAX_AVATAR_SIZE = 512;
    private static final long MAX_AVATAR_CACHE_BYTES = 64L * 1024 * 1024;
    private static final int MAX_REMEMBERED_MISSES = 10_000;
    private static final Duration MISS_LIFETIME = Duration.ofMinutes(2);

    private final AvatarUpstream upstream;
    private final Semaphore fetchPermits = new Semaphore(MAX_CONCURRENT_FETCHES);
    private final Cache<String, byte[]> avatarCache = Caffeine.newBuilder()
        .maximumWeight(MAX_AVATAR_CACHE_BYTES)
        .weigher((String key, byte[] body) -> body.length)
        .expireAfterWrite(Duration.ofMinutes(10))
        .build();
    private final Cache<String, Boolean> missingAvatars = Caffeine.newBuilder()
        .maximumSize(MAX_REMEMBERED_MISSES)
        .expireAfterWrite(MISS_LIFETIME)
        .build();

    @Autowired
    public CrafatarProxyService() {
        this(new HttpAvatarUpstream());
    }

    CrafatarProxyService(AvatarUpstream upstream) {
        this.upstream = upstream;
    }

    public byte @Nullable [] getAvatar(String uuid, int size, boolean overlay) {
        int clampedSize = Math.max(MIN_AVATAR_SIZE, Math.min(size, MAX_AVATAR_SIZE));
        String cacheKey = clampedSize + ":" + overlay + ":" + uuid;
        byte[] cached = avatarCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }
        if (missingAvatars.getIfPresent(cacheKey) != null || !fetchPermits.tryAcquire()) {
            return null;
        }
        try {
            byte[] fetched = upstream.fetch(uuid, clampedSize, overlay);
            if (fetched == null) {
                missingAvatars.put(cacheKey, Boolean.TRUE);
            } else {
                avatarCache.put(cacheKey, fetched);
            }
            return fetched;
        } finally {
            fetchPermits.release();
        }
    }
}
