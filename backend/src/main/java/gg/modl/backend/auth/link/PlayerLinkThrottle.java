package gg.modl.backend.auth.link;

import gg.modl.backend.infrastructure.exception.TooManyRequestsException;
import gg.modl.backend.infrastructure.ratelimit.BucketPool;
import gg.modl.backend.infrastructure.ratelimit.BucketSpec;
import gg.modl.backend.infrastructure.util.UuidUtils;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PlayerLinkThrottle {
    private static final BucketSpec PER_PLAYER = new BucketSpec("PLAYER_PANEL_LINK", 5, Duration.ofMinutes(1));
    private static final String RATE_LIMITED_CODE = "RATE_LIMITED";

    private final BucketPool bucketPool;

    public void requireAdmission(String minecraftUuid) {
        ConsumptionProbe probe = bucketPool.resolveBucket(PER_PLAYER, UuidUtils.normalize(minecraftUuid))
            .tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            throw new TooManyRequestsException("Too many link requests for this player; try again shortly",
                RATE_LIMITED_CODE, Duration.ofNanos(probe.getNanosToWaitForRefill()));
        }
    }
}
