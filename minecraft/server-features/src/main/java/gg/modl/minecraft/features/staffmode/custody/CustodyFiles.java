package gg.modl.minecraft.features.staffmode.custody;

import gg.modl.minecraft.core.util.DurableFiles;
import gg.modl.minecraft.core.util.Java8Collections;
import gg.modl.minecraft.core.util.PluginLogger;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class CustodyFiles implements CustodyStore {
    static final String DIRECTORY = "staff-custody";
    static final String RECORD_SUFFIX = ".custody";
    static final String SET_ASIDE_SUFFIX = ".kept";

    private static final long CLOSE_TIMEOUT_SECONDS = 5;

    private final Path directory;
    private final PluginLogger logger;
    private final ExecutorService diskThread = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "modl-staff-custody");
        thread.setDaemon(true);
        return thread;
    });

    private boolean closed;

    public CustodyFiles(Path dataFolder, PluginLogger logger) {
        this.directory = dataFolder.resolve(DIRECTORY);
        this.logger = logger;
    }

    @Override
    public CompletableFuture<Optional<CustodyRecord>> load(UUID player) {
        return onDiskThread(() -> read(player));
    }

    @Override
    public CompletableFuture<@Nullable Void> save(UUID player, CustodyRecord record) {
        return onDiskThread(() -> {
            DurableFiles.replace(fileOf(player), CustodyFormat.write(record));
            syncDirectory(directory);
            Path parent = directory.toAbsolutePath().getParent();
            if (parent != null) syncDirectory(parent);
            return null;
        });
    }

    @Override
    public CompletableFuture<@Nullable Void> remove(UUID player) {
        return onDiskThread(() -> {
            Files.deleteIfExists(fileOf(player));
            syncDirectory(directory);
            return null;
        });
    }

    @Override
    public CompletableFuture<String> setAside(UUID player) {
        return onDiskThread(() -> {
            Path kept = directory.resolve(player + "." + System.currentTimeMillis() + "." + UUID.randomUUID() + SET_ASIDE_SUFFIX);
            Files.move(fileOf(player), kept);
            syncDirectory(directory);
            return kept.toString();
        });
    }

    @Override
    public String describe(UUID player) {
        return fileOf(player).toString();
    }

    @Override
    public void close() {
        synchronized (this) {
            closed = true;
            diskThread.shutdown();
        }
        try {
            if (!diskThread.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                logger.warning("[staff-mode] Saving staff custody in " + directory + " did not finish before shutdown");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Optional<CustodyRecord> read(UUID player) throws IOException {
        try {
            return Optional.of(CustodyFormat.read(Files.readAllBytes(fileOf(player))));
        } catch (NoSuchFileException noRecord) {
            return Optional.empty();
        }
    }

    private Path fileOf(UUID player) {
        return directory.resolve(player + RECORD_SUFFIX);
    }

    private static void syncDirectory(Path path) {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException directorySyncUnsupported) {
            return;
        }
    }

    private synchronized <T extends @Nullable Object> CompletableFuture<T> onDiskThread(DiskTask<T> task) {
        if (closed) return Java8Collections.failedFuture(new IOException("staff custody storage is closed"));
        CompletableFuture<T> result = new CompletableFuture<>();
        diskThread.execute(() -> {
            try {
                result.complete(task.run());
            } catch (IOException | RuntimeException | LinkageError failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private interface DiskTask<T extends @Nullable Object> {
        T run() throws IOException;
    }
}
