package gg.modl.backend.storage.service;

import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.infrastructure.util.ByteFormatUtil;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.storage.MediaCategory;
import gg.modl.backend.storage.StorageLimitPolicy;
import gg.modl.backend.storage.StorageNamespace;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class MediaValidationService {
    private static final Set<String> DANGEROUS_EXTENSIONS = Set.of(
        ".exe", ".bat", ".cmd", ".com", ".msi", ".scr", ".pif",
        ".js", ".vbs", ".wsf", ".jar", ".sh", ".ps1", ".php",
        ".asp", ".aspx", ".jsp", ".cgi", ".pl", ".py", ".rb",
        ".html", ".htm", ".svg", ".xhtml"
    );

    private final StorageLimitPolicy storageLimitPolicy;

    public Optional<String> rejectionOf(String fileName, String contentType, long fileSize, String uploadType) {
        if (fileName == null || fileName.isBlank()) {
            return Optional.of("File name is required");
        }
        if (contentType == null || contentType.isBlank()) {
            return Optional.of("Content type is required");
        }
        Optional<String> dangerous = dangerousExtension(fileName);
        if (dangerous.isPresent()) {
            log.warn("Blocked a presign request for a file ending in {}", dangerous.get());
            return Optional.of("File type not allowed");
        }
        Optional<MediaCategory> category = MediaCategory.fromUploadType(uploadType);
        if (category.isEmpty()) {
            return Optional.of("Invalid upload type");
        }
        if (!category.get().allows(contentType)) {
            return Optional.of("File type not allowed for " + uploadType + ". Allowed: " + category.get().contentTypes());
        }
        long maxSize = storageLimitPolicy.maxUploadBytes(category.get());
        if (fileSize > maxSize) {
            return Optional.of("File exceeds maximum size of " + ByteFormatUtil.formatCompact(maxSize));
        }
        if (fileSize <= 0) {
            return Optional.of("Invalid file size");
        }
        return Optional.empty();
    }

    public static Optional<String> dangerousExtension(String fileName) {
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        return DANGEROUS_EXTENSIONS.stream().filter(lowerName::endsWith).findFirst();
    }

    public Map<String, Object> getAllSupportedTypes() {
        Map<String, Object> supportedTypes = new LinkedHashMap<>();
        for (MediaCategory category : MediaCategory.values()) {
            supportedTypes.put(category.configKey(), category.contentTypes());
        }
        return supportedTypes;
    }

    public long maxUploadBytes(MediaCategory category) {
        return storageLimitPolicy.maxUploadBytes(category);
    }

    public Map<String, Object> getAllSizeLimits() {
        Map<String, Object> sizeLimits = new LinkedHashMap<>();
        for (MediaCategory category : MediaCategory.values()) {
            sizeLimits.put(category.configKey(), storageLimitPolicy.maxUploadBytes(category));
        }
        return sizeLimits;
    }

    public boolean isKeyInNamespace(String key) {
        if (key == null || !key.startsWith(StorageNamespace.KEY_PREFIX)) {
            return false;
        }
        return !hasPathTraversal(key);
    }

    public void assertKeyOwnedByServer(Server server, String key) {
        if (!isKeyInNamespace(key)) {
            throw new ForbiddenException("Access denied");
        }
    }

    private boolean hasPathTraversal(String key) {
        for (String segment : key.split("/")) {
            if (segment.equals("..")) {
                return true;
            }
        }
        return false;
    }

    public String extractUploadType(String key) {
        return StorageKeyUtils.folderOf(key);
    }

    public @Nullable String extractEntityId(String key) {
        String[] parts = key.split("/");
        return parts.length >= 4 ? parts[2] : null;
    }

}
