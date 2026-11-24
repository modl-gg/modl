package gg.modl.backend.hostedimport.job;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ImportWorker {
    static final String THREAD_NAME = "modl-hosted-import";
    private static final long JOIN_SECONDS = 5;

    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, THREAD_NAME);
        thread.setDaemon(true);
        return thread;
    });

    public void submit(Runnable task) {
        if (stopping.get()) {
            return;
        }
        executor.execute(task);
    }

    public void cancel(String importId) {
        cancelled.add(importId);
    }

    public boolean isStopping() {
        return stopping.get();
    }

    public BooleanSupplier stopSignal(String importId) {
        return () -> stopping.get() || cancelled.contains(importId);
    }

    @EventListener(ContextClosedEvent.class)
    public void stop() {
        if (!stopping.compareAndSet(false, true)) {
            return;
        }
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(JOIN_SECONDS, TimeUnit.SECONDS)) {
                log.warn("The import worker did not stop within {} seconds", JOIN_SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
