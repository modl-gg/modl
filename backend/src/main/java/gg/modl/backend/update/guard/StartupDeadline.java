package gg.modl.backend.update.guard;

import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.infrastructure.scheduling.RepeatingTasks;
import gg.modl.backend.update.Deployment;
import gg.modl.backend.update.DeploymentDetector;
import gg.modl.update.EmbeddedUpdateContract;
import gg.modl.update.UpdateTransaction;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class StartupDeadline {
    public static final Duration LIMIT = Duration.ofMinutes(15);

    private final OperatorNotice notice;
    private final Deployment deployment;
    private final boolean supervisedElsewhere;
    private final LongSupplier nanoClock;
    private final Object lock = new Object();
    private @Nullable ScheduledExecutorService timer;
    private long startedNanos;
    private long pausedNanos;
    private long pauseBeganNanos = -1;
    private boolean reported;

    @Autowired
    public StartupDeadline(OperatorNotice notice, DeploymentDetector deployment) {
        this(notice, deployment.deployment(), System.getenv(EmbeddedUpdateContract.LAUNCHER_ENVIRONMENT) != null, System::nanoTime);
    }

    StartupDeadline(OperatorNotice notice, Deployment deployment, boolean launcherChild, LongSupplier nanoClock) {
        this.notice = notice;
        this.deployment = deployment;
        this.supervisedElsewhere = deployment == Deployment.EMBEDDED || launcherChild;
        this.nanoClock = nanoClock;
    }

    public void begin() {
        synchronized (lock) {
            startedNanos = nanoClock.getAsLong();
        }
    }

    public void pause() {
        synchronized (lock) {
            if (pauseBeganNanos < 0) {
                pauseBeganNanos = nanoClock.getAsLong();
            }
        }
    }

    public void resume() {
        synchronized (lock) {
            if (pauseBeganNanos >= 0) {
                pausedNanos += nanoClock.getAsLong() - pauseBeganNanos;
                pauseBeganNanos = -1;
            }
        }
    }

    public void enforce(UpdateTransaction transaction) {
        if (supervisedElsewhere) {
            return;
        }
        synchronized (lock) {
            if (timer != null) {
                return;
            }
            ScheduledExecutorService started = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "modl-update-deadline");
                thread.setDaemon(true);
                return thread;
            });
            timer = started;
            RepeatingTasks.withFixedDelay(started, () -> reportIfLate(transaction), 1, 1, TimeUnit.SECONDS);
        }
    }

    @PreDestroy
    public void cancel() {
        synchronized (lock) {
            if (timer != null) {
                timer.shutdownNow();
                timer = null;
            }
        }
    }

    boolean reportIfLate(UpdateTransaction transaction) {
        synchronized (lock) {
            long now = nanoClock.getAsLong();
            long pausedNow = pauseBeganNanos >= 0 ? now - pauseBeganNanos : 0;
            if (reported || now - startedNanos - pausedNanos - pausedNow < LIMIT.toNanos()) {
                return false;
            }
            reported = true;
        }
        notice.publish("modl {} has been starting for more than {} minutes after the update from {}. It keeps running, because"
            + " nothing here can return to the previous version by itself. If it never finishes: {}", transaction.to(),
            LIMIT.toMinutes(), transaction.from(), UpdateFailureListener.wayBack(transaction, deployment));
        cancel();
        return true;
    }
}
