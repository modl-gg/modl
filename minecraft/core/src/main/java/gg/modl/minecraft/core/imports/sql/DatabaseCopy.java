package gg.modl.minecraft.core.imports.sql;

import gg.modl.minecraft.core.imports.support.FileTrees;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public final class DatabaseCopy implements AutoCloseable {
    private static final String OWNER_ONLY = "rwx------";
    private static final long SPACE_MARGIN_BYTES = 256L * 1024 * 1024;
    private static final long BYTES_PER_MEBIBYTE = 1024L * 1024;
    private static final List<String> SKIPPED_SUFFIXES = Arrays.asList(".lck", ".lock.db", ".trace.db", ".tmp");

    private final Path directory;
    private final Path base;

    private DatabaseCopy(Path directory, Path base) {
        this.directory = directory;
        this.base = base;
    }

    public static DatabaseCopy of(Path scratch, Path original) throws IOException {
        Path folder = original.toAbsolutePath().getParent();
        if (folder == null) throw new IOException(original + " has no folder");
        String baseName = String.valueOf(original.getFileName());
        requireSpace(scratch, folder, baseName);
        Path directory = scratch.toAbsolutePath().resolve(UUID.randomUUID().toString());
        Files.createDirectories(directory);
        if (Files.getFileStore(directory).supportsFileAttributeView(PosixFileAttributeView.class)) {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString(OWNER_ONLY));
        }
        DatabaseCopy copy = new DatabaseCopy(directory, directory.resolve(baseName));
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, baseName + ".*")) {
            for (Path file : files) {
                if (Files.isRegularFile(file) && !isSkipped(file.getFileName().toString())) {
                    Files.copy(file, directory.resolve(file.getFileName()), StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        } catch (IOException | RuntimeException failure) {
            copy.close();
            throw failure;
        }
        return copy;
    }

    public static boolean exists(Path original, String suffix) {
        return Files.isRegularFile(original.resolveSibling(original.getFileName() + suffix));
    }

    public Path base() {
        return base;
    }

    @Override
    public void close() {
        FileTrees.deleteQuietly(directory);
    }

    private static void requireSpace(Path scratch, Path folder, String baseName) throws IOException {
        long needed = SPACE_MARGIN_BYTES;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, baseName + ".*")) {
            for (Path file : files) {
                if (Files.isRegularFile(file) && !isSkipped(file.getFileName().toString())) needed += Files.size(file);
            }
        }
        Path existing = scratch;
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        if (existing == null) return;
        long usable = Files.getFileStore(existing).getUsableSpace();
        if (usable < needed) {
            throw new NoSpaceException(needed / BYTES_PER_MEBIBYTE + " MB needed, " + usable / BYTES_PER_MEBIBYTE + " MB free");
        }
    }

    private static boolean isSkipped(String name) {
        for (String suffix : SKIPPED_SUFFIXES) {
            if (name.endsWith(suffix)) return true;
        }
        return false;
    }

    public static final class NoSpaceException extends IOException {
        NoSpaceException(String detail) {
            super(detail);
        }
    }
}
