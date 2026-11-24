package gg.modl.backend.infrastructure.ratelimit;

import java.time.Duration;

public record BucketSpec(String namespace, int capacity, Duration refillDuration) {
}
