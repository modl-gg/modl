package gg.modl.backend.ai.protocol;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import gg.modl.backend.ai.model.AiConnection;
import gg.modl.backend.ai.model.AiRequestShape;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class AiShapeMemory {
    private static final int CAPACITY = 256;
    private static final Duration LIFETIME = Duration.ofHours(1);

    private final Cache<String, VerifiedShape> verified = Caffeine.newBuilder()
        .maximumSize(CAPACITY)
        .expireAfterWrite(LIFETIME)
        .build();

    public void remember(AiConnection connection, AiRequestShape shape, Instant verifiedAt) {
        verified.put(connection.fingerprint(), new VerifiedShape(shape, verifiedAt));
    }

    public Optional<VerifiedShape> recall(AiConnection connection) {
        return Optional.ofNullable(verified.getIfPresent(connection.fingerprint()));
    }

    public record VerifiedShape(AiRequestShape shape, Instant verifiedAt) {
    }
}
