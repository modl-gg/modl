package gg.modl.minecraft.replay.recording;

import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

final class SerialExecutor implements Executor {
    private final Executor pool;
    private final Consumer<RuntimeException> failures;
    private final Queue<Runnable> tasks = new ArrayDeque<>();
    private boolean running;

    SerialExecutor(Executor pool, Consumer<RuntimeException> failures) {
        this.pool = pool;
        this.failures = failures;
    }

    @Override
    public void execute(Runnable task) {
        synchronized (tasks) {
            tasks.add(task);
            if (running) return;
            running = true;
        }
        schedule();
    }

    private void schedule() {
        try {
            pool.execute(this::drain);
        } catch (RejectedExecutionException e) {
            synchronized (tasks) {
                tasks.clear();
                running = false;
            }
            throw e;
        }
    }

    private void drain() {
        boolean drained = false;
        try {
            while (true) {
                @Nullable Runnable task;
                synchronized (tasks) {
                    task = tasks.poll();
                    if (task == null) {
                        running = false;
                        drained = true;
                        return;
                    }
                }
                try {
                    task.run();
                } catch (RuntimeException e) {
                    failures.accept(e);
                }
            }
        } finally {
            if (!drained) resumeAfterError();
        }
    }

    private void resumeAfterError() {
        synchronized (tasks) {
            if (tasks.isEmpty()) {
                running = false;
                return;
            }
        }
        try {
            schedule();
        } catch (RejectedExecutionException ignored) {
        }
    }
}
