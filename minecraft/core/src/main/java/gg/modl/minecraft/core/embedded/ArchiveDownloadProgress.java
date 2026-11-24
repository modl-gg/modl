package gg.modl.minecraft.core.embedded;

import com.alessiodp.libby.download.TransferListener;
import gg.modl.minecraft.core.util.PluginLogger;

import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

final class ArchiveDownloadProgress implements TransferListener {
    private final PluginLogger logger;
    private final StartupProgress startup;
    private final BooleanSupplier cancelled;
    private final long totalBytes;
    private long lastReportNanos;

    ArchiveDownloadProgress(PluginLogger logger, StartupProgress startup, BooleanSupplier cancelled, long totalBytes) {
        this.logger = logger;
        this.startup = startup;
        this.cancelled = cancelled;
        this.totalBytes = totalBytes;
        this.lastReportNanos = startup.nowNanos();
    }

    @Override
    public void transferred(long attemptBytes) {
        if (cancelled.getAsBoolean()) throw new CancellationException("The built-in panel start was cancelled");
        startup.advance();
        long now = startup.nowNanos();
        if (now - lastReportNanos < DownloadProgress.REPORT_INTERVAL_NANOS) return;
        lastReportNanos = now;
        logger.info("Downloading the built-in panel: " + Megabytes.amount(attemptBytes) + " of " + Megabytes.of(totalBytes) + ".");
    }
}
