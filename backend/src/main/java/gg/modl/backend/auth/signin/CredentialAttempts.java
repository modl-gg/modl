package gg.modl.backend.auth.signin;

import gg.modl.backend.auth.AuthFailures;
import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.infrastructure.ratelimit.BucketPool;
import gg.modl.backend.infrastructure.ratelimit.BucketSpec;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CredentialAttempts {
    static final BucketSpec PASSWORD_CLIENT = new BucketSpec("PASSWORD_CLIENT", 5, Duration.ofMinutes(15));
    static final BucketSpec PASSWORD_IDENTIFIER = new BucketSpec("PASSWORD_IDENTIFIER", 50, Duration.ofHours(1));
    static final BucketSpec SECOND_FACTOR_SHORT = new BucketSpec("SECOND_FACTOR_SHORT", 5, Duration.ofMinutes(15));
    static final BucketSpec SECOND_FACTOR_DAILY = new BucketSpec("SECOND_FACTOR_DAILY", 20, Duration.ofHours(24));
    private static final String PAIR_SEPARATOR = "|";

    private final BucketPool bucketPool;

    public AttemptReservation reservePassword(String email, Optional<String> clientNetwork) {
        String identity = identityKey(email);
        Optional<Bucket> pair = clientNetwork.map(network -> bucketPool.resolveBucket(PASSWORD_CLIENT, identity + PAIR_SEPARATOR + network));
        List<Bucket> buckets = new ArrayList<>();
        pair.ifPresent(buckets::add);
        buckets.add(bucketPool.resolveBucket(PASSWORD_IDENTIFIER, identity));
        return reserve(buckets, pair.orElse(null));
    }

    public AttemptReservation reserveSecondFactor(String email) {
        String identity = identityKey(email);
        Bucket shortWindow = bucketPool.resolveBucket(SECOND_FACTOR_SHORT, identity);
        return reserve(List.of(shortWindow, bucketPool.resolveBucket(SECOND_FACTOR_DAILY, identity)), shortWindow);
    }

    private static AttemptReservation reserve(List<Bucket> buckets, @Nullable Bucket clearedOnSuccess) {
        List<Bucket> consumed = new ArrayList<>();
        long longestWaitNanos = 0;
        for (Bucket bucket : buckets) {
            ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
            if (probe.isConsumed()) {
                consumed.add(bucket);
            } else {
                longestWaitNanos = Math.max(longestWaitNanos, probe.getNanosToWaitForRefill());
            }
        }
        if (consumed.size() < buckets.size()) {
            consumed.forEach(bucket -> bucket.addTokens(1));
            throw AuthFailures.tooManyAttempts(Duration.ofNanos(longestWaitNanos));
        }
        return new AttemptReservation(consumed, clearedOnSuccess);
    }

    private static String identityKey(String email) {
        String identity = EmailAddressUtil.normalize(email);
        return identity == null ? "" : identity;
    }
}
