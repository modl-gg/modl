package gg.modl.backend.storage.service;

import gg.modl.backend.server.data.Server;
import gg.modl.backend.storage.MediaCategory;
import gg.modl.backend.storage.StorageFolders;
import gg.modl.backend.storage.StorageKeySegments;
import gg.modl.backend.storage.StorageNamespace;
import org.jspecify.annotations.Nullable;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

public final class StorageKeyUtils {
    private static final Pattern DISALLOWED_CHARACTERS = Pattern.compile("[^a-zA-Z0-9._-]");
    private static final Pattern LEADING_DOTS = Pattern.compile("^\\.+");
    private static final int MAX_SEGMENT_LENGTH = 128;

    private StorageKeyUtils() {
    }

    public static String buildKey(Server server, String uploadType, String fileName, @Nullable String entityId) {
        String safeUploadType = sanitizeSegment(uploadType, "other");
        String safeFileName = sanitizeFileName(fileName);

        if (entityId != null && !entityId.isBlank()) {
            String safeEntityId = sanitizeSegment(TempUploadKeys.canonicalEntityId(entityId), UUID.randomUUID().toString());
            String folder = MediaCategory.fromUploadType(safeUploadType).map(MediaCategory::entityFolder).orElse(safeUploadType);
            String uniqueName = UUID.randomUUID() + "-" + safeFileName;
            return StorageNamespace.KEY_PREFIX + String.join("/", folder, safeEntityId, uniqueName);
        }

        int dotIndex = safeFileName.lastIndexOf('.');
        String extension = dotIndex >= 0 ? safeFileName.substring(dotIndex) : "";
        return folderPrefix(server, uploadType) + UUID.randomUUID() + extension;
    }

    public static String folderPrefix(Server server, String uploadType) {
        return StorageNamespace.KEY_PREFIX + sanitizeSegment(uploadType, "other") + "/";
    }

    public static String folderOf(String key) {
        String[] segments = key.split("/");
        return segments.length >= 2 ? segments[1] : "";
    }

    public static Optional<MediaCategory> mediaCategoryOf(String key) {
        return MediaCategory.fromUploadType(normalizeUploadType(folderOf(key)));
    }

    public static String parentPrefix(String key) {
        return key.substring(0, key.lastIndexOf('/') + 1);
    }

    public static String extractFileName(String key) {
        return key.substring(key.lastIndexOf("/") + 1);
    }

    public static String categorizeFile(String key) {
        if (key.contains("/evidence/")) {
            return "evidence";
        }
        if (key.contains("/tickets/") || key.contains("/ticket/")) {
            return "ticket";
        }
        if (key.contains("/logs/")) {
            return "logs";
        }
        if (key.contains("/backup/")) {
            return "backup";
        }
        if (key.contains("/" + StorageFolders.REPLAYS + "/")) {
            return "replay";
        }
        return "other";
    }

    public static String normalizeUploadType(String uploadType) {
        return MediaCategory.TICKET.entityFolder().equals(uploadType) ? MediaCategory.TICKET.uploadType() : uploadType;
    }

    public static String stripLeadingSlash(String key) {
        return key.startsWith("/") ? key.substring(1) : key;
    }

    private static String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "upload.bin";
        }

        String basename = fileName.replace('\\', '/');
        int slashIndex = basename.lastIndexOf('/');
        if (slashIndex >= 0) {
            basename = basename.substring(slashIndex + 1);
        }

        return sanitizeAllowed(basename, "upload.bin");
    }

    private static String sanitizeSegment(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }

        return sanitizeAllowed(value, fallback);
    }

    private static String sanitizeAllowed(String value, String fallback) {
        String sanitized = LEADING_DOTS.matcher(DISALLOWED_CHARACTERS.matcher(value).replaceAll("_")).replaceFirst("");
        if (sanitized.isBlank()) {
            return fallback;
        }
        String bounded = sanitized.length() > MAX_SEGMENT_LENGTH ? sanitized.substring(0, MAX_SEGMENT_LENGTH) : sanitized;
        return StorageKeySegments.isWindowsDeviceName(bounded) ? "_" + bounded : bounded;
    }
}
