package gg.modl.backend.auth.signin;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import gg.modl.backend.auth.session.AuthMethod;
import gg.modl.backend.auth.totp.SecondFactorMethod;
import gg.modl.backend.infrastructure.util.IdGenerator;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SecondFactorChallenges {
    static final Duration LIFETIME = Duration.ofMinutes(5);
    static final int MAX_FAILURES = 5;
    private static final int MAX_CHALLENGES = 4096;

    private final Cache<String, Entry> challenges = Caffeine.newBuilder()
        .expireAfterWrite(LIFETIME)
        .maximumSize(MAX_CHALLENGES)
        .build();
    private final IdGenerator idGenerator;

    public SecondFactorChallenge open(String email, AuthMethod method, @Nullable String expectedPasswordHash,
                                      List<SecondFactorMethod> methods) {
        String token = idGenerator.generateToken();
        SecondFactorChallenge challenge = new SecondFactorChallenge(token, email, method, expectedPasswordHash, methods,
            LIFETIME.toSeconds());
        challenges.put(token, new Entry(challenge, new AtomicInteger()));
        return challenge;
    }

    public Optional<SecondFactorChallenge> find(String token) {
        return Optional.ofNullable(challenges.getIfPresent(token)).map(Entry::challenge);
    }

    public void recordFailure(String token) {
        Entry entry = challenges.getIfPresent(token);
        if (entry != null && entry.failures().incrementAndGet() >= MAX_FAILURES) {
            challenges.invalidate(token);
        }
    }

    public void close(String token) {
        challenges.invalidate(token);
    }

    private record Entry(SecondFactorChallenge challenge, AtomicInteger failures) {
    }
}
