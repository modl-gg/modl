package gg.modl.backend.analytics.repository;

import java.time.Instant;

public record TimeBucketCount(Instant bucketStart, long count) {
}
