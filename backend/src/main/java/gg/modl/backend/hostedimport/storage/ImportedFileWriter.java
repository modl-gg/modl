package gg.modl.backend.hostedimport.storage;

import gg.modl.backend.hostedimport.archive.HostedArchive;
import gg.modl.backend.hostedimport.archive.VerifiedEntryStream;
import gg.modl.backend.hostedimport.model.ImportFailure;
import gg.modl.backend.hostedimport.model.ImportReason;
import gg.modl.backend.storage.ConfirmedStorageTotal;
import gg.modl.backend.storage.ObjectStorage;
import gg.modl.backend.storage.StorageLimitExceededException;
import gg.modl.backend.storage.StoredObject;
import gg.modl.backend.storage.StoredObjectExistsException;
import gg.modl.backend.storage.UploadLimits;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImportedFileWriter {
    private static final int BUFFER = 64 * 1024;

    private final ObjectStorage objectStorage;
    private final UploadLimits uploadLimits;
    private final ConfirmedStorageTotal confirmedStorageTotal;

    public Outcome write(HostedArchive archive, ImportedKeyPlan.PlannedObject planned, long pendingBytes) {
        String key = planned.localKey();
        long size = planned.source().size();
        Optional<StoredObject> existing = stat(key);
        if (existing.isPresent()) {
            return existing.get().size() == size && planned.source().sha256().equals(storedSha256(key))
                ? Outcome.ALREADY_STORED
                : Outcome.CONFLICT;
        }
        if (uploadLimits.limitsTotalStorage()
            && confirmedStorageTotal.bytes() + pendingBytes + size > uploadLimits.maxStorageBytes()) {
            throw new ImportFailure(ImportReason.STORAGE_LIMIT);
        }
        try (VerifiedEntryStream source = archive.open(planned.source().path(), size, planned.source().sha256())) {
            store(key, source, size, planned.contentType());
            verifyOrRemove(key, source);
            return Outcome.WRITTEN;
        } catch (StoredObjectExistsException e) {
            return Outcome.CONFLICT;
        } catch (IOException e) {
            throw new ImportFailure(ImportReason.ARCHIVE_MISSING, e);
        }
    }

    private void store(String key, InputStream source, long size, String contentType) {
        try {
            objectStorage.write(key, source, size, contentType);
        } catch (StoredObjectExistsException | ImportFailure e) {
            throw e;
        } catch (StorageLimitExceededException e) {
            throw new ImportFailure(ImportReason.STORAGE_LIMIT, e);
        } catch (RuntimeException e) {
            throw new ImportFailure(ImportReason.STORAGE_UNAVAILABLE, e);
        }
    }

    private void verifyOrRemove(String key, VerifiedEntryStream source) throws IOException {
        try {
            source.drainAndVerify();
        } catch (ImportFailure e) {
            objectStorage.delete(key);
            throw e;
        }
    }

    private Optional<StoredObject> stat(String key) {
        try {
            return objectStorage.stat(key);
        } catch (RuntimeException e) {
            throw new ImportFailure(ImportReason.STORAGE_UNAVAILABLE, e);
        }
    }

    private String storedSha256(String key) {
        MessageDigest digest = sha256();
        try (InputStream stored = objectStorage.read(key)) {
            byte[] buffer = new byte[BUFFER];
            int read;
            while ((read = stored.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        } catch (IOException | UncheckedIOException e) {
            throw new ImportFailure(ImportReason.STORAGE_UNAVAILABLE, e);
        } catch (RuntimeException e) {
            log.debug("An existing object could not be read back for comparison", e);
            return "";
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public enum Outcome {
        WRITTEN,
        ALREADY_STORED,
        CONFLICT
    }
}
