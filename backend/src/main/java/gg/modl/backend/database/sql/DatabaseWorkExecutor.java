package gg.modl.backend.database.sql;

import gg.modl.backend.database.DatabaseBusyException;
import gg.modl.backend.database.DatabaseUnavailableException;
import gg.modl.backend.database.DatabaseUnavailableException.Reason;
import gg.modl.backend.database.InFlightUnits;
import gg.modl.backend.database.ReportExecutor;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public final class DatabaseWorkExecutor implements ReportExecutor, InFlightUnits, AutoCloseable {
    public static final int JOB_THREADS = 2;

    private final ThreadPoolExecutor reports;
    private final ThreadPoolExecutor jobs;

    public DatabaseWorkExecutor(int reportThreads, int jobThreads) {
        this.reports = pool(reportThreads, "modl-db-report-");
        this.jobs = pool(jobThreads, "modl-db-job-");
    }

    @Override
    public <T> T run(Supplier<T> report) {
        return await(submit(reports, report::get));
    }

    public void runJob(Runnable job) {
        await(submit(jobs, () -> {
            job.run();
            return null;
        }));
    }

    @Override
    public int inFlight() {
        return reports.getActiveCount() + jobs.getActiveCount() + reports.getQueue().size() + jobs.getQueue().size();
    }

    public boolean isAccepting() {
        return !reports.isShutdown() && !jobs.isShutdown();
    }

    public void stopAccepting() {
        reports.shutdown();
        jobs.shutdown();
    }

    @Override
    public void close() {
        reports.shutdownNow();
        jobs.shutdownNow();
    }

    private static <T> Future<T> submit(ThreadPoolExecutor pool, Callable<T> task) {
        try {
            return pool.submit(task);
        } catch (RejectedExecutionException e) {
            throw new DatabaseUnavailableException(Reason.SHUTTING_DOWN);
        }
    }

    private static <T> T await(Future<T> future) {
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new DatabaseBusyException("Interrupted while waiting for database work", DatabaseBusyException.LANE_RETRY_AFTER, e);
        }
    }

    private static ThreadPoolExecutor pool(int threads, String namePrefix) {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, namePrefix + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), factory);
    }
}
