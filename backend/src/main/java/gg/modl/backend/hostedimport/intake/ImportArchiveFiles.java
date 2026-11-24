package gg.modl.backend.hostedimport.intake;

import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.hostedimport.model.ImportFailure;
import gg.modl.backend.hostedimport.model.ImportReason;
import gg.modl.backend.infrastructure.disk.DiskSpaceFloor;
import gg.modl.backend.infrastructure.disk.DurableFiles;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ImportArchiveFiles {
    private static final String DIRECTORY = "imports";
    private static final String SUFFIX = ".zip";
    private static final String POSIX = "posix";
    private static final Pattern IMPORT_ID = Pattern.compile("[0-9a-f-]{36}");
    private static final Set<PosixFilePermission> OWNER_ONLY_FILE = PosixFilePermissions.fromString("rw-------");
    private static final int BUFFER = 64 * 1024;

    private final Path directory;
    private final DiskSpaceFloor diskSpaceFloor;

    @Autowired
    public ImportArchiveFiles(DataDirectoryProperties dataDirectory) {
        this(dataDirectory.root().resolve(DIRECTORY), DiskSpaceFloor.DEFAULT);
    }

    ImportArchiveFiles(Path directory, DiskSpaceFloor diskSpaceFloor) {
        this.directory = directory;
        this.diskSpaceFloor = diskSpaceFloor;
    }

    public Path pathOf(String importId) {
        if (!IMPORT_ID.matcher(importId).matches()) {
            throw new IllegalArgumentException("Not an import id");
        }
        return directory.resolve(importId + SUFFIX);
    }

    public void create(String importId) {
        Path file = pathOf(importId);
        try {
            DurableFiles.createOwnerOnlyDirectories(directory);
            Files.deleteIfExists(file);
            Files.createFile(file, ownerOnly());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private FileAttribute<?>[] ownerOnly() throws IOException {
        return Files.getFileStore(directory).supportsFileAttributeView(POSIX)
            ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE)}
            : new FileAttribute<?>[0];
    }

    public long append(String importId, long offset, InputStream body, long length) {
        try (FileChannel channel = FileChannel.open(pathOf(importId), StandardOpenOption.WRITE)) {
            if (channel.size() < offset) {
                throw new ImportFailure(ImportReason.ARCHIVE_INCOMPLETE);
            }
            channel.truncate(offset);
            channel.position(offset);
            byte[] buffer = new byte[BUFFER];
            long written = 0;
            while (written < length) {
                int read = body.read(buffer, 0, (int) Math.min(buffer.length, length - written));
                if (read < 0) {
                    break;
                }
                writeFully(channel, ByteBuffer.wrap(buffer, 0, read));
                written += read;
            }
            channel.force(false);
            return written;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeFully(FileChannel channel, ByteBuffer chunk) {
        try {
            while (chunk.hasRemaining()) {
                channel.write(chunk);
            }
        } catch (IOException e) {
            throw new ImportFailure(ImportReason.DISK_SPACE, e);
        }
    }

    public boolean exists(String importId) {
        return Files.isRegularFile(pathOf(importId));
    }

    public long size(String importId) {
        try {
            return Files.size(pathOf(importId));
        } catch (IOException e) {
            throw new ImportFailure(ImportReason.ARCHIVE_MISSING, e);
        }
    }

    public void truncate(String importId, long size) {
        try (FileChannel channel = FileChannel.open(pathOf(importId), StandardOpenOption.WRITE)) {
            channel.truncate(size);
            channel.force(false);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String sha256(String importId, BooleanSupplier stopRequested) {
        MessageDigest digest = newDigest();
        try (InputStream input = Files.newInputStream(pathOf(importId))) {
            byte[] buffer = new byte[BUFFER];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (stopRequested.getAsBoolean()) {
                    throw new ImportFailure(ImportReason.CANCELLED);
                }
                digest.update(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new ImportFailure(ImportReason.ARCHIVE_MISSING, e);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public void delete(String importId) {
        try {
            Files.deleteIfExists(pathOf(importId));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public long shortfall(long incomingBytes) {
        try {
            return diskSpaceFloor.shortfall(fileStore(), incomingBytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public long availableBytes() {
        try {
            FileStore store = fileStore();
            long usable = store.getUsableSpace();
            long floor = Math.max(diskSpaceFloor.minimumFreeBytes(), (long) (store.getTotalSpace() * diskSpaceFloor.minimumFreeFraction()));
            return Math.max(0, usable - floor);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private FileStore fileStore() throws IOException {
        DurableFiles.createOwnerOnlyDirectories(directory);
        return Files.getFileStore(directory);
    }

    public List<StoredArchive> archives() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString())
                .filter(name -> name.endsWith(SUFFIX))
                .map(name -> name.substring(0, name.length() - SUFFIX.length()))
                .filter(id -> IMPORT_ID.matcher(id).matches())
                .map(this::storedArchive)
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private StoredArchive storedArchive(String importId) {
        try {
            return new StoredArchive(importId, Files.getLastModifiedTime(pathOf(importId)).toInstant());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public record StoredArchive(String importId, Instant lastModified) {
    }
}
