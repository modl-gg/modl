package gg.modl.backend.storage;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Stream;

public enum MediaCategory {
    EVIDENCE("evidence", "evidence", Formats.ATTACHMENTS, OptionalLong.empty()),
    TICKET("ticket", "tickets", Formats.ATTACHMENTS, OptionalLong.empty()),
    APPEAL("appeal", "appeals", Formats.ATTACHMENTS, OptionalLong.empty()),
    ARTICLE("article", "articles", Formats.ARTICLE_MEDIA, OptionalLong.of(50L * Formats.MEGABYTE)),
    SERVER_ICON("server-icon", "server-icons", Formats.IMAGES, OptionalLong.of(10L * Formats.MEGABYTE)),
    REPLAY("replay", "replays", Set.of("application/octet-stream"), OptionalLong.of(10L * Formats.MEGABYTE));

    private static final String TICKET_ENTITY_FOLDER = "tickets";

    private final String uploadType;
    private final String configKey;
    private final Set<String> contentTypes;
    private final OptionalLong intrinsicLimitBytes;

    MediaCategory(String uploadType, String configKey, Set<String> contentTypes, OptionalLong intrinsicLimitBytes) {
        this.uploadType = uploadType;
        this.configKey = configKey;
        this.contentTypes = contentTypes;
        this.intrinsicLimitBytes = intrinsicLimitBytes;
    }

    public static Optional<MediaCategory> fromUploadType(String uploadType) {
        return Arrays.stream(values())
            .filter(category -> category.uploadType.equals(uploadType))
            .findFirst();
    }

    public String uploadType() {
        return uploadType;
    }

    public String entityFolder() {
        return this == TICKET ? TICKET_ENTITY_FOLDER : uploadType;
    }

    public List<String> keyFolders() {
        return Stream.of(uploadType, entityFolder(), configKey).distinct().toList();
    }

    public String configKey() {
        return configKey;
    }

    public boolean allows(String contentType) {
        return contentTypes.contains(contentType);
    }

    public boolean attachedOnSubmission() {
        return this == EVIDENCE || this == TICKET || this == APPEAL;
    }

    public List<String> contentTypes() {
        return contentTypes.stream().sorted().toList();
    }

    long limitBytes(long configuredMaxUploadBytes) {
        return intrinsicLimitBytes.isPresent()
            ? Math.min(intrinsicLimitBytes.getAsLong(), configuredMaxUploadBytes)
            : configuredMaxUploadBytes;
    }

    private static final class Formats {
        private static final long MEGABYTE = 1024L * 1024;
        private static final Set<String> IMAGES = Set.of("image/png", "image/jpeg", "image/gif", "image/apng", "image/webp");
        private static final Set<String> ATTACHMENTS = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/apng", "image/webp",
            "video/mp4", "video/webm", "video/quicktime", "video/x-matroska",
            "application/pdf", "text/plain", "text/markdown"
        );
        private static final Set<String> ARTICLE_MEDIA = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/apng", "text/plain", "text/markdown"
        );
    }
}
