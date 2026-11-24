package gg.modl.backend.retention;

import java.util.function.IntToLongFunction;

public final class RetentionBatches {
    public static final int DEFAULT_BATCH_SIZE = 500;
    public static final int DEFAULT_MAX_BATCHES_PER_RUN = 200;

    private RetentionBatches() {
    }

    public static long drain(int batchSize, int maxBatches, IntToLongFunction deleteBatch) {
        return drainAtMost(Long.MAX_VALUE, batchSize, maxBatches, deleteBatch);
    }

    public static long drainAtMost(long total, int batchSize, int maxBatches, IntToLongFunction deleteBatch) {
        long removed = 0;
        for (int batch = 0; batch < maxBatches && removed < total; batch++) {
            int size = (int) Math.min(batchSize, total - removed);
            long deleted = deleteBatch.applyAsLong(size);
            removed += deleted;
            if (deleted < size) {
                break;
            }
        }
        return removed;
    }
}
