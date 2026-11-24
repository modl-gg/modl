package gg.modl.backend.infrastructure.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.TimeMeter;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class BucketPool {

    private static final int MAX_BUCKETS_PER_NAMESPACE = 50_000;
    private static final Duration IDLE_EXPIRY_MARGIN = Duration.ofMinutes(1);

    private final Map<BucketSpec, Cache<String, Bucket>> namespaces = new ConcurrentHashMap<>();
    private final TimeMeter timeMeter;

    public BucketPool() {
        this(TimeMeter.SYSTEM_MILLISECONDS);
    }

    BucketPool(TimeMeter timeMeter) {
        this.timeMeter = timeMeter;
    }

    public Bucket resolveBucket(BucketSpec spec, String key) {
        return namespaces
            .computeIfAbsent(spec, BucketPool::newNamespace)
            .get(key, ignored -> createBucket(spec));
    }

    private static Cache<String, Bucket> newNamespace(BucketSpec spec) {
        return Caffeine.newBuilder()
            .maximumSize(MAX_BUCKETS_PER_NAMESPACE)
            .expireAfterAccess(spec.refillDuration().plus(IDLE_EXPIRY_MARGIN))
            .build();
    }

    private Bucket createBucket(BucketSpec spec) {
        Bandwidth limit = Bandwidth.builder()
            .capacity(spec.capacity())
            .refillGreedy(spec.capacity(), spec.refillDuration())
            .build();
        return Bucket.builder().withCustomTimePrecision(timeMeter).addLimit(limit).build();
    }
}
