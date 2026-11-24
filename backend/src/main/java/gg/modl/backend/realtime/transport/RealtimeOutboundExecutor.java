package gg.modl.backend.realtime.transport;

import gg.modl.backend.realtime.config.RealtimeProperties;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RealtimeOutboundExecutor {
    private static final long SHUTDOWN_GRACE_SECONDS = 2;
    private static final long IDLE_WORKER_SECONDS = 60;

    private final Executor workers;

    @Autowired
    public RealtimeOutboundExecutor(RealtimeProperties properties) {
        this(boundedWorkers(properties.getOutboundMaxWorkers()));
    }

    public RealtimeOutboundExecutor(Executor workers) {
        this.workers = workers;
    }

    boolean tryExecute(Runnable drain) {
        try {
            workers.execute(drain);
            return true;
        } catch (RejectedExecutionException rejected) {
            return false;
        }
    }

    @PreDestroy
    public void shutdown() throws InterruptedException {
        if (workers instanceof ExecutorService service) {
            service.shutdown();
            service.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS);
        }
    }

    static ExecutorService boundedWorkers(int maxWorkers) {
        return new ThreadPoolExecutor(0, maxWorkers, IDLE_WORKER_SECONDS, TimeUnit.SECONDS,
            new SynchronousQueue<>(), daemonThreads(), new ThreadPoolExecutor.AbortPolicy());
    }

    private static ThreadFactory daemonThreads() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "realtime-outbox-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
