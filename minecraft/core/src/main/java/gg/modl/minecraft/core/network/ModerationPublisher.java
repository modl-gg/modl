package gg.modl.minecraft.core.network;

import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.core.util.Scheduling;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public final class ModerationPublisher {
    public static final long REPUBLISH_INTERVAL_SECONDS = 30;
    private static final String THREAD_NAME = "modl-moderation";

    private final Supplier<ModerationSnapshot> snapshots;
    private final Executor executor;
    private final Runnable shutdown;
    private final PluginLogger logger;
    private final List<ModerationStateSink> sinks = new CopyOnWriteArrayList<>();
    private final AtomicBoolean publishPending = new AtomicBoolean();

    ModerationPublisher(Supplier<ModerationSnapshot> snapshots, Executor executor, Runnable shutdown,
                        PluginLogger logger) {
        this.snapshots = snapshots;
        this.executor = executor;
        this.shutdown = shutdown;
        this.logger = logger;
    }

    public static ModerationPublisher start(Supplier<ModerationSnapshot> snapshots, PluginLogger logger) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
        ModerationPublisher publisher = new ModerationPublisher(snapshots, scheduler, scheduler::shutdownNow, logger);
        Scheduling.withFixedDelay(scheduler, publisher::republish, REPUBLISH_INTERVAL_SECONDS,
                REPUBLISH_INTERVAL_SECONDS, TimeUnit.SECONDS, logger);
        return publisher;
    }

    public void changed() {
        if (publishPending.compareAndSet(false, true)) submit(this::publishPendingChange);
    }

    public void addSink(ModerationStateSink sink) {
        sinks.add(sink);
        submit(() -> deliver(sink, snapshots.get()));
    }

    public void removeSink(ModerationStateSink sink) {
        sinks.remove(sink);
    }

    public void stop() {
        sinks.clear();
        shutdown.run();
    }

    void republish() {
        if (sinks.isEmpty()) return;
        publishToAll(snapshots.get());
    }

    private void publishPendingChange() {
        publishPending.set(false);
        publishToAll(snapshots.get());
    }

    private void publishToAll(ModerationSnapshot snapshot) {
        for (ModerationStateSink sink : sinks) deliver(sink, snapshot);
    }

    private void deliver(ModerationStateSink sink, ModerationSnapshot snapshot) {
        try {
            sink.publish(snapshot);
        } catch (RuntimeException failure) {
            logger.warning("[network] Failed to publish the moderation state to " + sink.getClass().getSimpleName()
                    + ": " + failure);
        }
    }

    private void submit(Runnable task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException stopped) {
            publishPending.set(false);
        }
    }
}
