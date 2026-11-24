package gg.modl.update;

import org.jspecify.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

public final class VerifiedCopy implements Closeable {
    static final String LOCK_FILE = ".in-use";
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final Set<PosixFilePermission> OWNER_DIRECTORY =
        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);

    private final Path directory;
    private final Path file;
    private final FileChannel lockChannel;

    private VerifiedCopy(Path directory, Path file, FileChannel lockChannel) {
        this.directory = directory;
        this.file = file;
        this.lockChannel = lockChannel;
    }

    public static VerifiedCopy of(Path source, ReleaseArtifact artifact, Path runDirectory) throws IOException {
        if (!source.getFileName().toString().equals(artifact.file())) {
            throw new ArtifactMismatchException(source + " is not named " + artifact.file());
        }
        Files.createDirectories(runDirectory);
        removeAbandoned(runDirectory);
        Path directory = Files.createDirectory(runDirectory.resolve(UUID.randomUUID().toString()));
        restrictDirectoryToOwner(directory);
        FileChannel lockChannel = null;
        try {
            lockChannel = FileChannel.open(directory.resolve(LOCK_FILE), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            lockChannel.lock();
            Path file = directory.resolve(artifact.file());
            copyHashing(source, file, artifact);
            return new VerifiedCopy(directory, file, lockChannel);
        } catch (IOException | RuntimeException failure) {
            closeQuietly(lockChannel);
            deleteQuietly(directory);
            throw failure;
        }
    }

    public Path file() {
        return file;
    }

    @Override
    public void close() {
        closeQuietly(lockChannel);
        deleteQuietly(directory);
    }

    static void removeAbandoned(Path runDirectory) {
        try (DirectoryStream<Path> copies = Files.newDirectoryStream(runDirectory)) {
            for (Path copy : copies) {
                if (Files.isDirectory(copy) && !Files.isSymbolicLink(copy) && !inUse(copy)) {
                    deleteQuietly(copy);
                }
            }
        } catch (IOException ignored) {
            return;
        }
    }

    private static boolean inUse(Path copy) {
        try (FileChannel channel = FileChannel.open(copy.resolve(LOCK_FILE), StandardOpenOption.WRITE)) {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                return true;
            }
            lock.release();
            return false;
        } catch (OverlappingFileLockException heldHere) {
            return true;
        } catch (IOException missingOrUnreadable) {
            return false;
        }
    }

    private static void copyHashing(Path source, Path target, ReleaseArtifact artifact) throws IOException {
        MessageDigest digest = ArtifactFiles.newDigest();
        long copied = 0;
        byte[] buffer = new byte[BUFFER_BYTES];
        try (InputStream input = Files.newInputStream(source);
             OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            UpdateFiles.restrictToOwner(target);
            int read;
            while ((read = input.read(buffer)) >= 0) {
                copied += read;
                if (copied > artifact.size()) {
                    throw new ArtifactMismatchException(source + " is larger than the " + artifact.size() + " bytes the release lists");
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        if (copied != artifact.size()) {
            throw new ArtifactMismatchException(source + " has " + copied + " bytes, the release lists " + artifact.size());
        }
        if (!Hex.encode(digest.digest()).equals(artifact.sha256())) {
            throw new ArtifactMismatchException(source + " does not match the SHA-256 the release lists");
        }
    }

    private static void restrictDirectoryToOwner(Path directory) {
        try {
            Files.setPosixFilePermissions(directory, OWNER_DIRECTORY);
        } catch (UnsupportedOperationException | IOException ignored) {
            return;
        }
    }

    private static void closeQuietly(@Nullable FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            return;
        }
    }

    private static void deleteQuietly(Path directory) {
        try {
            UpdateFiles.deleteTree(directory);
        } catch (IOException stillOpen) {
            return;
        }
    }
}
