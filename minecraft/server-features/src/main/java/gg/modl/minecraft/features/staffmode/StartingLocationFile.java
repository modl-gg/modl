package gg.modl.minecraft.features.staffmode;

import gg.modl.minecraft.core.util.DurableFiles;
import gg.modl.minecraft.core.util.PluginLogger;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class StartingLocationFile implements StartingLocationStore {
    static final String FILE_NAME = "staff-mode-returns.txt";
    static final long MAX_BYTES = 256L * 1024L;

    private static final long CLOSE_TIMEOUT_SECONDS = 5;

    private final Path file;
    private final PluginLogger logger;
    private final AtomicReference<@Nullable Map<UUID, PendingReturn>> unsaved = new AtomicReference<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "modl-staff-mode-returns");
        thread.setDaemon(true);
        return thread;
    });

    private boolean closed;
    private boolean saveFailureReported;

    public StartingLocationFile(Path dataFolder, PluginLogger logger) {
        this.file = dataFolder.resolve(FILE_NAME);
        this.logger = logger;
    }

    @Override
    public synchronized CompletableFuture<Map<UUID, PendingReturn>> load() {
        if (closed) return CompletableFuture.completedFuture(Collections.emptyMap());
        return CompletableFuture.supplyAsync(this::read, writer);
    }

    private Map<UUID, PendingReturn> read() {
        if (!Files.exists(file)) return Collections.emptyMap();
        try {
            if (Files.size(file) > MAX_BYTES) throw new IOException("it is larger than " + MAX_BYTES + " bytes");
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            Map<UUID, PendingReturn> pendingReturns = StartingLocationFormat.read(lines);
            int skipped = lines.size() - 1 - pendingReturns.size();
            if (skipped > 0) logger.warning("[staff-mode] Skipped " + skipped + " unreadable lines in " + file);
            return pendingReturns;
        } catch (IOException e) {
            logger.warning("[staff-mode] Could not read " + file
                    + "; staff who were in staff mode before the restart stay where they are: " + e);
            return Collections.emptyMap();
        }
    }

    @Override
    public synchronized void save(Map<UUID, PendingReturn> pendingReturns) {
        if (closed) return;
        unsaved.set(pendingReturns);
        writer.execute(this::writeNewest);
    }

    @Override
    public void close() {
        synchronized (this) {
            closed = true;
            writer.shutdown();
        }
        try {
            if (!writer.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                logger.warning("[staff-mode] Saving " + file + " did not finish before shutdown");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void writeNewest() {
        Map<UUID, PendingReturn> newest = unsaved.getAndSet(null);
        if (newest == null) return;
        try {
            if (newest.isEmpty()) Files.deleteIfExists(file);
            else DurableFiles.replace(file, StartingLocationFormat.write(newest).getBytes(StandardCharsets.UTF_8));
            saveFailureReported = false;
        } catch (IOException e) {
            if (saveFailureReported) return;
            saveFailureReported = true;
            logger.warning("[staff-mode] Could not save " + file
                    + "; pending returns from staff mode will not survive a restart: " + e);
        }
    }
}
