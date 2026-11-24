package gg.modl.minecraft.core.imports;

import gg.modl.minecraft.core.imports.source.SourceReader;
import gg.modl.minecraft.core.util.PluginLogger;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class PreviewedReader {
    private final ScheduledExecutorService monitor;
    private final PluginLogger logger;
    private @Nullable Kept kept;

    PreviewedReader(ScheduledExecutorService monitor, PluginLogger logger) {
        this.monitor = monitor;
        this.logger = logger;
    }

    void keep(String sourceId, SourceReader reader) {
        Kept entry = new Kept(sourceId, reader);
        Kept replaced;
        synchronized (this) {
            replaced = kept;
            kept = entry;
        }
        if (replaced != null) replaced.reader.close();
        try {
            entry.expiry = monitor.schedule(() -> expire(entry), ImportConfirmation.VALID_FOR.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException stopped) {
            expire(entry);
        }
    }

    synchronized @Nullable SourceReader take(String sourceId) {
        Kept entry = kept;
        if (entry == null || !entry.sourceId.equals(sourceId)) return null;
        kept = null;
        entry.stopExpiry();
        return entry.reader;
    }

    void discardLater() {
        Kept stale;
        synchronized (this) {
            stale = kept;
            kept = null;
        }
        if (stale == null) return;
        try {
            monitor.execute(() -> close(stale));
        } catch (RejectedExecutionException stopped) {
            logger.debug("The import monitor has already stopped");
        }
    }

    private static void close(Kept entry) {
        entry.stopExpiry();
        entry.reader.close();
    }

    private void expire(Kept entry) {
        synchronized (this) {
            if (kept != entry) return;
            kept = null;
        }
        entry.reader.close();
    }

    private static final class Kept {
        private final String sourceId;
        private final SourceReader reader;
        private volatile @Nullable ScheduledFuture<?> expiry;

        private Kept(String sourceId, SourceReader reader) {
            this.sourceId = sourceId;
            this.reader = reader;
        }

        private void stopExpiry() {
            ScheduledFuture<?> scheduled = expiry;
            if (scheduled != null) scheduled.cancel(false);
        }
    }
}
