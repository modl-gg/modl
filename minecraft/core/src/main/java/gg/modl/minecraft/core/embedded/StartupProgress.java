package gg.modl.minecraft.core.embedded;

import gg.modl.update.EmbeddedUpdateContract;
import gg.modl.update.UpdatePhase;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

public final class StartupProgress {
    public static final long STALL_TIMEOUT_SECONDS = 180;
    public static final long UPDATE_STALL_TIMEOUT_SECONDS = TimeUnit.MINUTES.toSeconds(15);
    public static final long BACKUP_STALL_TIMEOUT_SECONDS = TimeUnit.MINUTES.toSeconds(60);

    private final LongSupplier nanoClock;
    private final AtomicLong lastAdvanceNanos;
    private volatile long stallTimeoutSeconds = STALL_TIMEOUT_SECONDS;
    private volatile boolean updating;

    public StartupProgress() {
        this(System::nanoTime);
    }

    public StartupProgress(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
        this.lastAdvanceNanos = new AtomicLong(nanoClock.getAsLong());
    }

    public void advance() {
        lastAdvanceNanos.set(nanoClock.getAsLong());
    }

    public void beginStart() {
        updating = false;
        stallTimeoutSeconds = STALL_TIMEOUT_SECONDS;
        advance();
    }

    public void reportPhase(String phase) {
        updating = true;
        stallTimeoutSeconds = backsUp(phase) ? BACKUP_STALL_TIMEOUT_SECONDS : UPDATE_STALL_TIMEOUT_SECONDS;
        advance();
    }

    public long stallTimeoutSeconds() {
        return stallTimeoutSeconds;
    }

    public boolean updating() {
        return updating;
    }

    long idleNanos() {
        return nanoClock.getAsLong() - lastAdvanceNanos.get();
    }

    public long idleNanosSince(long startNanos) {
        long now = nanoClock.getAsLong();
        return Math.min(now - lastAdvanceNanos.get(), now - startNanos);
    }

    public long nowNanos() {
        return nanoClock.getAsLong();
    }

    private static boolean backsUp(String phase) {
        return UpdatePhase.BACKING_UP.name().equals(phase) || EmbeddedUpdateContract.PHASE_BACKUP_HEARTBEAT.equals(phase);
    }
}
