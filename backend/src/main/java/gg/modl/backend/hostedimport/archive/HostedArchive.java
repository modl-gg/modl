package gg.modl.backend.hostedimport.archive;

import gg.modl.backend.hostedimport.model.ImportFailure;
import gg.modl.backend.hostedimport.model.ImportReason;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

@Slf4j
public final class HostedArchive implements Closeable {
    public static final String MANIFEST = "manifest.json";

    private final ZipFile zip;
    private final Map<String, ZipEntry> entries;
    private final ZipEntry manifest;

    private HostedArchive(ZipFile zip, Map<String, ZipEntry> entries, ZipEntry manifest) {
        this.zip = zip;
        this.entries = entries;
        this.manifest = manifest;
    }

    public static HostedArchive open(Path path) {
        CentralDirectoryCheck.read(path);
        ZipFile zip;
        try {
            zip = new ZipFile(path.toFile(), StandardCharsets.UTF_8);
        } catch (ZipException e) {
            throw new ImportFailure(ImportReason.ARCHIVE_NOT_ZIP, e);
        } catch (IOException e) {
            throw new ImportFailure(ImportReason.ARCHIVE_DAMAGED, e);
        }
        try {
            Map<String, ZipEntry> entries = checkedEntries(zip, Files.size(path));
            ZipEntry manifest = entries.get(MANIFEST);
            if (manifest == null) {
                throw new ImportFailure(ImportReason.ARCHIVE_INCOMPLETE);
            }
            return new HostedArchive(zip, entries, manifest);
        } catch (IOException | RuntimeException e) {
            closeQuietly(zip);
            if (e instanceof ImportFailure failure) {
                throw failure;
            }
            throw new ImportFailure(ImportReason.ARCHIVE_DAMAGED, e);
        }
    }

    private static Map<String, ZipEntry> checkedEntries(ZipFile zip, long fileSize) {
        Map<String, ZipEntry> byName = new HashMap<>();
        long compressedTotal = 0;
        int count = 0;
        Enumeration<? extends ZipEntry> all = zip.entries();
        while (all.hasMoreElements()) {
            ZipEntry entry = all.nextElement();
            count++;
            if (count > ArchiveLimits.MAX_ENTRIES) {
                throw new ImportFailure(ImportReason.ARCHIVE_TOO_MANY_FILES);
            }
            if (entry.isDirectory() || entry.getMethod() != ZipEntry.DEFLATED) {
                throw new ImportFailure(ImportReason.ARCHIVE_ENTRIES);
            }
            long compressed = entry.getCompressedSize();
            if (compressed < 0 || entry.getSize() < 0) {
                throw new ImportFailure(ImportReason.ARCHIVE_DAMAGED);
            }
            compressedTotal += compressed;
            if (compressedTotal > fileSize) {
                throw new ImportFailure(ImportReason.ARCHIVE_DAMAGED);
            }
            if (byName.putIfAbsent(entry.getName(), entry) != null) {
                throw new ImportFailure(ImportReason.ARCHIVE_ENTRIES);
            }
        }
        return byName;
    }

    public int entryCount() {
        return entries.size();
    }

    public boolean contains(String name) {
        return entries.containsKey(name);
    }

    public Optional<Long> declaredSize(String name) {
        return Optional.ofNullable(entries.get(name)).map(ZipEntry::getSize);
    }

    public VerifiedEntryStream open(String name, long declaredSize, @Nullable String expectedSha256) {
        ZipEntry entry = entries.get(name);
        if (entry == null || entry.getSize() != declaredSize) {
            throw new ImportFailure(ImportReason.ARCHIVE_ENTRIES);
        }
        try {
            InputStream data = zip.getInputStream(entry);
            return new VerifiedEntryStream(data, declaredSize, expectedSha256);
        } catch (ZipException e) {
            throw new ImportFailure(ImportReason.ARCHIVE_DAMAGED, e);
        } catch (IOException e) {
            throw new ImportFailure(ImportReason.ARCHIVE_MISSING, e);
        }
    }

    public VerifiedEntryStream openManifest() {
        long size = manifest.getSize();
        if (size > ArchiveLimits.MAX_MANIFEST_BYTES) {
            throw new ImportFailure(ImportReason.ARCHIVE_TOO_LARGE);
        }
        return open(MANIFEST, size, null);
    }

    @Override
    public void close() {
        closeQuietly(zip);
    }

    private static void closeQuietly(ZipFile zip) {
        try {
            zip.close();
        } catch (IOException e) {
            log.debug("Could not close the import archive", e);
        }
    }
}
