package gg.modl.backend.retention.repository;

import java.time.Instant;

public interface AgeRetainedRecords {
    long deleteOldestBatch(Instant createdBefore, int batchSize);
}
