package gg.modl.backend.storage.backend;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

final class TransferWakeup implements StorageTransferJob.Sleeper {
    private final Object monitor = new Object();
    private long wakeups;

    @Override
    public void sleep(Duration duration) throws InterruptedException {
        if (duration.isNegative() || duration.isZero()) {
            return;
        }
        synchronized (monitor) {
            long seen = wakeups;
            long deadline = System.nanoTime() + duration.toNanos();
            long remaining = duration.toNanos();
            while (wakeups == seen && remaining > 0) {
                TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
                remaining = deadline - System.nanoTime();
            }
        }
    }

    void wakeAll() {
        synchronized (monitor) {
            wakeups++;
            monitor.notifyAll();
        }
    }
}
