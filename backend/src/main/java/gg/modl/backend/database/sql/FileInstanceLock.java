package gg.modl.backend.database.sql;

import gg.modl.backend.infrastructure.disk.DurableFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class FileInstanceLock implements InstanceLock {
    public static final String LOCK_FILE_NAME = "modl.lock";
    static final String MARKER_PREFIX = "gg.modl.database.lock:";
    static final Duration POLL_INTERVAL = Duration.ofMillis(250);
    static final Duration WAIT_BUDGET = Duration.ofSeconds(30);

    private final String marker;
    private final String token;
    private final FileChannel channel;
    private final FileLock lock;
    private final LockRelease release;
    private boolean released;

    private FileInstanceLock(String marker, String token, FileChannel channel, FileLock lock, LockRelease release) {
        this.marker = marker;
        this.token = token;
        this.channel = channel;
        this.lock = lock;
        this.release = release;
    }

    static FileInstanceLock acquire(Path databaseDirectory, LockRelease release, Duration waitBudget) {
        Path lockFile = lockFile(databaseDirectory);
        String marker = markerName(lockFile);
        awaitMarkerAbsent(marker, waitBudget);
        FileChannel channel = open(lockFile);
        FileLock lock = lockOrFail(channel, databaseDirectory, waitBudget);
        String token = UUID.randomUUID().toString();
        if (System.getProperties().putIfAbsent(marker, token) != null) {
            RetainedLocks.retain(channel);
            throw new InstanceLockHeldException(restartRemedy());
        }
        return new FileInstanceLock(marker, token, channel, lock, release);
    }

    @Override
    public synchronized void destroy() {
        if (released) {
            return;
        }
        released = true;
        if (!release.connectionsClosed()) {
            RetainedLocks.retain(channel);
            log.warn("{} database connections are still open, so the database lock stays held until the process exits",
                release.openConnections());
            return;
        }
        release.releaseDrivers();
        try {
            lock.release();
            channel.close();
        } catch (IOException e) {
            log.warn("Could not release the database lock file", e);
        } finally {
            System.getProperties().remove(marker, token);
        }
    }

    static String markerName(Path lockFile) {
        return MARKER_PREFIX + lockFile;
    }

    static Path lockFile(Path databaseDirectory) {
        try {
            DurableFiles.createOwnerOnlyDirectories(databaseDirectory);
            return databaseDirectory.toRealPath().resolve(LOCK_FILE_NAME);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create the database directory " + databaseDirectory, e);
        }
    }

    private static void awaitMarkerAbsent(String marker, Duration waitBudget) {
        long deadline = System.nanoTime() + waitBudget.toNanos();
        while (System.getProperty(marker) != null) {
            if (System.nanoTime() >= deadline) {
                throw new InstanceLockHeldException(restartRemedy());
            }
            pause();
        }
    }

    private static FileChannel open(Path lockFile) {
        try {
            return FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not open the database lock file " + lockFile, e);
        }
    }

    private static FileLock lockOrFail(FileChannel channel, Path databaseDirectory, Duration waitBudget) {
        long deadline = System.nanoTime() + waitBudget.toNanos();
        while (true) {
            try {
                FileLock lock = channel.tryLock();
                if (lock == null) {
                    closeQuietly(channel);
                    throw new InstanceLockHeldException("Another process is using " + databaseDirectory
                        + ". Give each server its own data directory.");
                }
                return lock;
            } catch (OverlappingFileLockException e) {
                if (System.nanoTime() >= deadline) {
                    RetainedLocks.retain(channel);
                    throw new InstanceLockHeldException(restartRemedy(), e);
                }
                pause();
            } catch (IOException e) {
                closeQuietly(channel);
                throw new UncheckedIOException("Could not lock " + databaseDirectory, e);
            }
        }
    }

    private static String restartRemedy() {
        return "The previous modl server in this process has not released its database. Restart the Minecraft server.";
    }

    private static void pause() {
        try {
            Thread.sleep(POLL_INTERVAL);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the database lock", e);
        }
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException e) {
            log.debug("Could not close the database lock file", e);
        }
    }
}
