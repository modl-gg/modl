package gg.modl.backend.backup;

import org.jspecify.annotations.Nullable;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.StringJoiner;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class StorageArchive implements Closeable {
    private static final String ENTRY_PREFIX = "storage.files/";
    private static final String SUMMARY_ENTRY = "storage.properties";
    private static final long MAX_SUMMARY_BYTES = 64L * 1024;
    private static final String FILES_KEY = "files";
    private static final String BYTES_KEY = "bytes";
    private static final String SEPARATOR = "/";

    private final ZipFile archive;
    @Nullable
    private final ArchivedStorage summary;

    private StorageArchive(ZipFile archive, @Nullable ArchivedStorage summary) {
        this.archive = archive;
        this.summary = summary;
    }

    public static StorageArchive open(Path archivePath) throws IOException {
        ZipFile archive = new ZipFile(archivePath.toFile());
        try {
            StorageArchive storage = new StorageArchive(archive, readSummary(archive));
            storage.verify();
            return storage;
        } catch (IOException | RuntimeException e) {
            archive.close();
            throw e;
        }
    }

    public static ArchivedStorage write(StorageFolder folder, ZipOutputStream zip) throws IOException {
        long files = 0;
        long bytes = 0;
        zip.setLevel(Deflater.BEST_SPEED);
        for (StorageFolder.StoredFile file : folder.files()) {
            Optional<InputStream> content = openUnlessDeleted(file.path());
            if (content.isEmpty()) {
                continue;
            }
            try (InputStream input = content.get()) {
                zip.putNextEntry(new ZipEntry(entryName(file.relativePath())));
                bytes += input.transferTo(zip);
                zip.closeEntry();
            }
            files++;
        }
        zip.setLevel(Deflater.DEFAULT_COMPRESSION);
        ArchivedStorage written = new ArchivedStorage(files, bytes);
        zip.putNextEntry(new ZipEntry(SUMMARY_ENTRY));
        zip.write((FILES_KEY + "=" + files + "\n" + BYTES_KEY + "=" + bytes + "\n").getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        return written;
    }

    private static Optional<InputStream> openUnlessDeleted(Path file) throws IOException {
        try {
            return Optional.of(Files.newInputStream(file));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
    }

    public Optional<ArchivedStorage> summary() {
        return Optional.ofNullable(summary);
    }

    void extractTo(Path directory) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        for (ZipEntry entry : entries()) {
            Path target = root.resolve(relativePathOf(entry)).normalize();
            if (!target.startsWith(root) || target.equals(root)) {
                throw unsafeName(entry);
            }
            Files.createDirectories(target.getParent());
            try (VerifiedEntryStream input = VerifiedEntryStream.open(archive, entry)) {
                Files.copy(input, target);
                input.requireIntact();
            }
        }
    }

    @Override
    public void close() throws IOException {
        archive.close();
    }

    private void verify() throws IOException {
        long files = 0;
        long bytes = 0;
        for (ZipEntry entry : entries()) {
            relativePathOf(entry);
            try (VerifiedEntryStream input = VerifiedEntryStream.open(archive, entry)) {
                bytes += input.transferTo(OutputStream.nullOutputStream());
                input.requireIntact();
            }
            files++;
        }
        ArchivedStorage expected = summary != null ? summary : new ArchivedStorage(0, 0);
        if (files != expected.files() || bytes != expected.bytes()) {
            throw new IOException("Backup is damaged: it holds " + files + " uploaded files of " + bytes + " bytes, "
                + SUMMARY_ENTRY + " lists " + expected.files() + " files of " + expected.bytes() + " bytes");
        }
    }

    private List<? extends ZipEntry> entries() {
        return Collections.list(archive.entries()).stream()
            .filter(entry -> !entry.isDirectory() && entry.getName().startsWith(ENTRY_PREFIX))
            .toList();
    }

    @Nullable
    private static ArchivedStorage readSummary(ZipFile archive) throws IOException {
        ZipEntry entry = archive.getEntry(SUMMARY_ENTRY);
        if (entry == null) {
            return null;
        }
        Properties properties = new Properties();
        try (InputStream input = VerifiedEntryStream.openAtMost(archive, entry, MAX_SUMMARY_BYTES)) {
            properties.load(new ByteArrayInputStream(input.readAllBytes()));
        }
        try {
            long files = Long.parseLong(properties.getProperty(FILES_KEY, ""));
            long bytes = Long.parseLong(properties.getProperty(BYTES_KEY, ""));
            if (files < 0 || bytes < 0) {
                throw new NumberFormatException(files + "/" + bytes);
            }
            return new ArchivedStorage(files, bytes);
        } catch (NumberFormatException e) {
            throw new IOException("Backup is damaged: " + SUMMARY_ENTRY + " does not list valid counts", e);
        }
    }

    private static String entryName(Path relativePath) {
        StringJoiner name = new StringJoiner(SEPARATOR, ENTRY_PREFIX, "");
        relativePath.forEach(segment -> name.add(segment.toString()));
        return name.toString();
    }

    private static String relativePathOf(ZipEntry entry) throws IOException {
        String relative = entry.getName().substring(ENTRY_PREFIX.length());
        for (String segment : relative.split(SEPARATOR, -1)) {
            if (!isSafeSegment(segment)) {
                throw unsafeName(entry);
            }
        }
        return relative;
    }

    private static boolean isSafeSegment(String segment) {
        return !segment.isEmpty() && !segment.equals(".") && !segment.equals("..")
            && segment.chars().noneMatch(character -> character < ' ' || character == '\\' || character == ':');
    }

    private static IOException unsafeName(ZipEntry entry) {
        return new IOException("Backup is damaged: " + entry.getName() + " is not a valid file name");
    }
}
