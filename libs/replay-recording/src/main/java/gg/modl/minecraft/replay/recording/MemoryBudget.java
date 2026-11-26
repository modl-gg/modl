package gg.modl.minecraft.replay.recording;

import java.util.concurrent.atomic.AtomicLong;

final class MemoryBudget {
    static final long MIN_RECORDING_BYTES = 2L * 1024 * 1024;
    private static final long LOW_WATER_PERCENT = 90;

    private volatile long limitBytes;
    private final AtomicLong liveBytes = new AtomicLong();
    private final AtomicLong reservedBytes = new AtomicLong();

    MemoryBudget(long limitBytes) {
        this.limitBytes = limitBytes;
    }

    void reserve(long bytes) {
        liveBytes.addAndGet(bytes);
    }

    void release(long bytes) {
        liveBytes.addAndGet(-bytes);
    }

    boolean reserveSnapshot(long bytes) {
        while (true) {
            long reserved = reservedBytes.get();
            if (reserved + bytes > limitBytes) return false;
            if (reservedBytes.compareAndSet(reserved, reserved + bytes)) return true;
        }
    }

    void adopt(long bytes) {
        liveBytes.addAndGet(-bytes);
        reservedBytes.addAndGet(bytes);
    }

    void releaseSnapshot(long bytes) {
        reservedBytes.addAndGet(-bytes);
    }

    long liveBytes() {
        return liveBytes.get();
    }

    long reservedBytes() {
        return reservedBytes.get();
    }

    long usedBytes() {
        return liveBytes.get() + reservedBytes.get();
    }

    void limit(long bytes) {
        limitBytes = bytes;
    }

    long limitBytes() {
        return limitBytes;
    }

    boolean exceeded() {
        return usedBytes() > limitBytes;
    }

    boolean admits(int recordingsAfterStart) {
        long available = limitBytes - reservedBytes.get();
        return available / Math.max(1, recordingsAfterStart) >= MIN_RECORDING_BYTES;
    }

    double keepFraction() {
        long live = liveBytes.get();
        if (live <= 0) return 1.0;
        long room = Math.max(0L, (limitBytes - reservedBytes.get()) * LOW_WATER_PERCENT / 100);
        return room >= live ? 1.0 : (double) room / live;
    }
}
