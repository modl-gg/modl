package gg.modl.minecraft.core.embedded;

import gg.modl.minecraft.core.util.PluginLogger;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

final class EmbeddedServerLock implements AutoCloseable {
    static final long RETRY_MILLIS = 250;
    private static final long STALL_MARGIN_SECONDS = 60;
    static final long PREVIOUS_START_WAIT_SECONDS = StartupProgress.STALL_TIMEOUT_SECONDS - STALL_MARGIN_SECONDS;
    private static final Queue<FileChannel> CHANNELS_SHARED_WITH_PREVIOUS_STARTS = new ConcurrentLinkedQueue<>();

    private final FileChannel channel;
    private final Path file;
    private final PluginLogger logger;

    private EmbeddedServerLock(FileChannel channel, Path file, PluginLogger logger) {
        this.channel = channel;
        this.file = file;
        this.logger = logger;
    }

    static EmbeddedServerLock acquire(Path file, BooleanSupplier cancelled, PluginLogger logger) throws IOException {
        return acquire(file, cancelled, logger, TimeUnit.SECONDS.toMillis(PREVIOUS_START_WAIT_SECONDS));
    }

    static EmbeddedServerLock acquire(Path file, BooleanSupplier cancelled, PluginLogger logger, long previousStartWaitMillis)
            throws IOException {
        Files.createDirectories(file.getParent());
        requireNotCancelled(cancelled);
        FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        boolean sharedWithPreviousStart = false;
        try {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(previousStartWaitMillis);
            while (!lockUnlessHeldByThisProcess(channel, file)) {
                sharedWithPreviousStart = true;
                requireNotCancelled(cancelled);
                if (System.nanoTime() - deadline >= 0) {
                    throw new EmbeddedServerException(previousStartStillRunning(file, previousStartWaitMillis));
                }
                pause();
            }
            return new EmbeddedServerLock(channel, file, logger);
        } catch (RuntimeException | Error failure) {
            if (sharedWithPreviousStart) keepOpenUntilExit(channel);
            else channel.close();
            throw failure;
        }
    }

    private static boolean lockUnlessHeldByThisProcess(FileChannel channel, Path file) {
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException previousStartStillRunning) {
            return false;
        } catch (IOException unsupported) {
            throw new EmbeddedServerException("Could not lock " + file + " (" + unsupported.getMessage()
                    + "). Keep the plugins/modl folder on a file system that supports file locks.", unsupported);
        }
        if (lock == null) throw new EmbeddedServerException(heldByAnotherProcess(file));
        return true;
    }

    private static void requireNotCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new CancellationException("The built-in panel start was cancelled");
    }

    private static synchronized void keepOpenUntilExit(FileChannel channel) {
        if (CHANNELS_SHARED_WITH_PREVIOUS_STARTS.isEmpty()) {
            Thread keeper = new Thread(EmbeddedServerLock::awaitExit, "modl-kept-lock-channels");
            keeper.setDaemon(true);
            keeper.start();
        }
        CHANNELS_SHARED_WITH_PREVIOUS_STARTS.add(channel);
    }

    private static void awaitExit() {
        new CompletableFuture<Void>().join();
    }

    static long keptOpenChannels() {
        return CHANNELS_SHARED_WITH_PREVIOUS_STARTS.stream().filter(FileChannel::isOpen).count();
    }

    private static void pause() {
        try {
            TimeUnit.MILLISECONDS.sleep(RETRY_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Interrupted while waiting for the previous built-in panel to stop");
        }
    }

    private static String previousStartStillRunning(Path file, long waitedMillis) {
        return "The built-in panel started before the reload did not stop within "
                + TimeUnit.MILLISECONDS.toSeconds(waitedMillis) + " seconds and still uses " + file.getParent() + ".";
    }

    private static String heldByAnotherProcess(Path file) {
        return "Another process is running the built-in panel from " + file.getParent()
                + ". Give every server its own plugins/modl folder.";
    }

    @Override
    public void close() {
        try {
            channel.close();
        } catch (IOException failure) {
            logger.debug("Could not release " + file + ": " + failure.getMessage());
        }
    }
}
