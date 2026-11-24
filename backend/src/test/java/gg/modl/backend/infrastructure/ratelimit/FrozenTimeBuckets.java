package gg.modl.backend.infrastructure.ratelimit;

import io.github.bucket4j.TimeMeter;

public final class FrozenTimeBuckets {
    private static final TimeMeter FROZEN_TIME = new TimeMeter() {
        @Override
        public long currentTimeNanos() {
            return 0L;
        }

        @Override
        public boolean isWallClockBased() {
            return false;
        }
    };

    private FrozenTimeBuckets() {
    }

    public static BucketPool pool() {
        return new BucketPool(FROZEN_TIME);
    }
}
