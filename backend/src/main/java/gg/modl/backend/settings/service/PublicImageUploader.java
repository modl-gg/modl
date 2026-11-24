package gg.modl.backend.settings.service;

import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.util.ByteFormatUtil;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.storage.ListedObject;
import gg.modl.backend.storage.MediaCategory;
import gg.modl.backend.storage.ObjectStorage;
import gg.modl.backend.storage.StorageLimitPolicy;
import gg.modl.backend.storage.StoredObjectExistsException;
import gg.modl.backend.storage.UploadRecordFailedException;
import gg.modl.backend.storage.service.StorageDeletionService;
import gg.modl.backend.storage.service.StorageKeyUtils;
import gg.modl.backend.storage.service.StorageMetadataService;
import gg.modl.backend.storage.service.StoredObjectUrlResolver;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Slf4j
public class PublicImageUploader {
    static final Duration SUPERSEDED_IMAGE_GRACE = Duration.ofHours(1);

    private static final String DEFAULT_IMAGE_NAME = "image";

    private final ObjectStorage objectStorage;
    private final StorageLimitPolicy storageLimitPolicy;
    private final StorageMetadataService storageMetadataService;
    private final StorageDeletionService storageDeletionService;
    private final StoredObjectUrlResolver storedObjectUrlResolver;
    private final Clock clock;

    public String upload(Server server, MultipartFile file, MediaCategory category, String folder, Supplier<String> referencedUrl) {
        String contentType = validatedContentType(file, category);
        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : DEFAULT_IMAGE_NAME;
        String key = StorageKeyUtils.buildKey(server, folder, fileName, null);
        storageLimitPolicy.reserve(key, file.getSize(), clock.instant());
        store(server, key, file, contentType);
        if (!storageMetadataService.confirmUpload(server, key, file.getSize(), contentType).recorded()) {
            storageDeletionService.discard(server, key);
            throw new UploadRecordFailedException();
        }
        removeSuperseded(server, folder, key, referencedUrl);
        return objectStorage.publicUrl(key);
    }

    private void store(Server server, String key, MultipartFile file, String contentType) {
        try (InputStream data = file.getInputStream()) {
            objectStorage.write(key, data, file.getSize(), contentType);
        } catch (StoredObjectExistsException e) {
            throw e;
        } catch (IOException e) {
            storageDeletionService.discard(server, key);
            throw new UncheckedIOException("Failed to read the uploaded image", e);
        } catch (RuntimeException e) {
            storageDeletionService.discard(server, key);
            throw e;
        }
    }

    private void removeSuperseded(Server server, String folder, String uploadedKey, Supplier<String> referencedUrl) {
        try {
            Optional<String> referencedKey = storedObjectUrlResolver.keyOf(referencedUrl.get());
            Instant graceCutoff = clock.instant().minus(SUPERSEDED_IMAGE_GRACE);
            List<String> superseded;
            try (Stream<ListedObject> images = objectStorage.list(StorageKeyUtils.folderPrefix(server, folder))) {
                superseded = images
                    .filter(image -> !image.key().equals(uploadedKey))
                    .filter(image -> referencedKey.filter(image.key()::equals).isEmpty())
                    .filter(image -> image.lastModified().isBefore(graceCutoff))
                    .map(ListedObject::key)
                    .toList();
            }
            storageDeletionService.delete(server, superseded);
        } catch (RuntimeException e) {
            log.warn("Failed to remove superseded images in {}", folder, e);
        }
    }

    private String validatedContentType(MultipartFile file, MediaCategory category) {
        if (file.isEmpty()) {
            throw new ValidationException("No file uploaded");
        }
        String contentType = file.getContentType();
        if (contentType == null || !category.allows(contentType)) {
            throw new ValidationException("Invalid file type. Allowed: PNG, JPEG, GIF, WebP");
        }
        long maxBytes = storageLimitPolicy.maxUploadBytes(category);
        if (file.getSize() > maxBytes) {
            throw new ValidationException("File too large. Maximum size is " + ByteFormatUtil.formatCompact(maxBytes) + ".");
        }
        return contentType;
    }
}
