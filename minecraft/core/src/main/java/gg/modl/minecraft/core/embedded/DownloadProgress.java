package gg.modl.minecraft.core.embedded;

import com.alessiodp.libby.download.TransferListener;
import gg.modl.minecraft.core.util.PluginLogger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class DownloadProgress implements DownloadListener {
    static final long REPORT_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5);

    private final PluginLogger logger;
    private final StartupProgress startup;
    private final Map<String, Long> transferredByFile = new ConcurrentHashMap<>();
    private final AtomicInteger completedFiles = new AtomicInteger();
    private final AtomicLong lastReportNanos = new AtomicLong();
    private volatile int totalFiles;
    private volatile long totalBytes;
    private volatile long startNanos;

    DownloadProgress(PluginLogger logger, StartupProgress startup) {
        this.logger = logger;
        this.startup = startup;
    }

    void begin(String version, int files, long bytes) {
        totalFiles = files;
        totalBytes = bytes;
        startNanos = startup.nowNanos();
        lastReportNanos.set(startNanos);
        startup.advance();
        logger.info("Downloading modl server " + version + " libraries from Maven Central: " + files + " files, "
                + Megabytes.of(bytes) + ".");
    }

    @Override
    public TransferListener listenerFor(ServerLibrary file) {
        return attemptBytes -> {
            transferredByFile.put(file.getFileName(), attemptBytes);
            startup.advance();
            reportIfDue();
        };
    }

    @Override
    public void completed(ServerLibrary file) {
        transferredByFile.put(file.getFileName(), file.getSize());
        completedFiles.incrementAndGet();
        startup.advance();
        reportIfDue();
    }

    String summary() {
        long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(startup.nowNanos() - startNanos);
        return "downloaded " + completedFiles.get() + " files, " + Megabytes.of(transferredBytes()) + ", in " + elapsedSeconds + "s";
    }

    private void reportIfDue() {
        long now = startup.nowNanos();
        long last = lastReportNanos.get();
        if (now - last < REPORT_INTERVAL_NANOS || !lastReportNanos.compareAndSet(last, now)) return;
        logger.info("Downloading modl server libraries: " + completedFiles.get() + " of " + totalFiles + " files, "
                + Megabytes.amount(transferredBytes()) + " of " + Megabytes.of(totalBytes) + ".");
    }

    private long transferredBytes() {
        long total = 0;
        for (long bytes : transferredByFile.values()) total += bytes;
        return total;
    }
}
