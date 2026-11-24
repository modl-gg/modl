package gg.modl.backend.ticket.service;

import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.player.data.punishment.PunishmentState;
import gg.modl.backend.storage.service.StoredObjectUrlResolver;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.dto.request.CreateTicketRequest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TicketContentService {
    static final int MAX_CHAT_MESSAGE_LENGTH = 256;
    static final int MAX_CHAT_MESSAGES = 50;

    private final StoredObjectUrlResolver urlResolver;

    private static Instant storableTimestamp(@Nullable Object value, Instant fallback) {
        Instant parsed = parseTimestamp(value, fallback);
        return parsed.isBefore(Instant.EPOCH) || parsed.isAfter(PunishmentState.MAX) ? fallback : parsed;
    }

    private static Instant parseTimestamp(@Nullable Object value, Instant fallback) {
        if (value instanceof Instant date) {
            return date;
        }
        if (value instanceof Number number) {
            return Instant.ofEpochMilli(number.longValue());
        }
        if (value instanceof String str && !str.isBlank()) {
            String trimmed = str.trim();
            try {
                return Instant.parse(trimmed);
            } catch (DateTimeParseException ignored) {
            }
            try {
                return Instant.ofEpochMilli(Long.parseLong(trimmed));
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    public String buildTicketContent(CreateTicketRequest request, Map<String, Object> formDataForContent) {
        StringBuilder content = new StringBuilder();

        if (request.description() != null && !request.description().isBlank()) {
            content.append("**Description:** ").append(request.description()).append("\n\n");
        }

        if (request.chatMessages() != null && !request.chatMessages().isEmpty()) {
            content.append("**Chat Messages:**\n");
            for (Map<String, Object> msg : request.chatMessages()) {
                if (msg.containsKey("username") && msg.containsKey("message")) {
                    String timestamp = Objects.requireNonNullElse(msg.get("timestamp"), "Unknown time").toString();
                    content.append(String.format("`[%s]` **%s**: %s\n", timestamp, msg.get("username"), msg.get("message")));
                }
            }
            content.append("\n");
        }

        String formDataContent = buildFormDataContent(formDataForContent, request.fieldLabels());
        if (!formDataContent.isEmpty()) {
            content.append(formDataContent);
        }

        return content.toString().trim();
    }

    public String buildFormDataContent(@Nullable Map<String, Object> formData, @Nullable Map<String, String> fieldLabels) {
        if (formData == null || formData.isEmpty()) {
            return "";
        }

        StringBuilder content = new StringBuilder();
        for (Map.Entry<String, Object> entry : formData.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().toString().isBlank()) {
                String heading = FormFieldLabels.heading(FormFieldLabels.resolve(entry.getKey(), fieldLabels));
                content.append("**").append(heading).append("** ").append(entry.getValue()).append("\n\n");
            }
        }
        return content.toString().trim();
    }

    public FormDataProcessingResult processFormDataForContent(@Nullable Map<String, Object> formData) {
        if (formData == null || formData.isEmpty()) {
            return new FormDataProcessingResult(Collections.emptyMap(), new ArrayList<>());
        }

        Map<String, Object> sanitizedFormData = new LinkedHashMap<>();
        List<Object> extractedAttachments = new ArrayList<>();

        for (Map.Entry<String, Object> entry : formData.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }

            if (value instanceof String textValue) {
                String trimmedValue = textValue.trim();
                if (trimmedValue.isBlank()) {
                    continue;
                }

                if (isLikelyAttachmentField(entry.getKey(), trimmedValue)) {
                    extractedAttachments.add(createAttachmentFromUrl(trimmedValue));
                    continue;
                }
            }

            sanitizedFormData.put(entry.getKey(), value);
        }

        return new FormDataProcessingResult(sanitizedFormData, extractedAttachments);
    }

    public List<Object> normalizeAttachments(@Nullable List<Object> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return new ArrayList<>();
        }

        List<Object> normalized = new ArrayList<>();

        for (Object attachment : attachments) {
            if (attachment == null) {
                continue;
            }

            if (attachment instanceof String attachmentUrl) {
                String trimmedUrl = attachmentUrl.trim();
                if (trimmedUrl.isBlank() || !isAttachableUrl(trimmedUrl)) {
                    continue;
                }
                normalized.add(createAttachmentFromUrl(trimmedUrl));
                continue;
            }

            if (attachment instanceof Map<?, ?> mapAttachment) {
                Map<String, Object> normalizedMap = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : mapAttachment.entrySet()) {
                    if (entry.getKey() != null) {
                        normalizedMap.put(entry.getKey().toString(), entry.getValue());
                    }
                }

                Object urlValue = normalizedMap.get("url");
                if (urlValue == null || urlValue.toString().isBlank()) {
                    continue;
                }

                String url = urlValue.toString().trim();
                if (!isAttachableUrl(url)) {
                    continue;
                }
                normalizedMap.put("url", url);
                normalizedMap.putIfAbsent("fileName", extractFileName(url));
                normalizedMap.putIfAbsent("fileType", inferFileType(url));
                normalizedMap.putIfAbsent("fileSize", 0);
                normalized.add(normalizedMap);
                continue;
            }

            normalized.add(attachment);
        }

        return dedupeAttachments(normalized);
    }

    public List<Object> mergeAttachments(@Nullable List<Object> explicitAttachments,
                                         @Nullable List<Object> inferredAttachments) {
        List<Object> merged = new ArrayList<>();
        if (explicitAttachments != null && !explicitAttachments.isEmpty()) {
            merged.addAll(explicitAttachments);
        }
        if (inferredAttachments != null && !inferredAttachments.isEmpty()) {
            merged.addAll(inferredAttachments);
        }
        return dedupeAttachments(merged);
    }

    @Nullable
    public String legacyFormDataEmail(@Nullable Map<String, Object> formData) {
        if (formData == null || formData.isEmpty()) {
            return null;
        }

        Object legacyEmail = formData.containsKey("contact_email")
                             ? formData.get("contact_email")
                             : formData.get("email");
        if (legacyEmail == null) {
            return null;
        }

        return EmailAddressUtil.normalizeIfValid(legacyEmail.toString());
    }

    public List<Ticket.ChatMessage> sanitizeChatMessages(List<Map<String, Object>> rawMessages, Instant now) {
        List<Map<String, Object>> trimmed = rawMessages.size() > MAX_CHAT_MESSAGES
                                            ? rawMessages.subList(rawMessages.size() - MAX_CHAT_MESSAGES, rawMessages.size())
                                            : rawMessages;

        return trimmed.stream()
            .map(x -> {
                Object rawContent = x.get("content");
                String content = rawContent instanceof String s ? s : Objects.toString(rawContent, "");
                if (content.length() > MAX_CHAT_MESSAGE_LENGTH) {
                    content = content.substring(0, MAX_CHAT_MESSAGE_LENGTH);
                }
                Object rawSender = x.get("sender");
                String sender = rawSender instanceof String s ? s : null;
                return Ticket.ChatMessage.builder()
                    .content(content)
                    .timestamp(storableTimestamp(x.get("timestamp"), now))
                    .sender(sender)
                    .build();
            })
            .toList();
    }

    private List<Object> dedupeAttachments(List<Object> attachments) {
        List<Object> deduped = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();

        for (Object attachment : attachments) {
            String url = extractAttachmentUrl(attachment);
            if (url != null) {
                String normalizedUrl = url.trim();
                if (normalizedUrl.isBlank() || !seenUrls.add(normalizedUrl)) {
                    continue;
                }
            }
            deduped.add(attachment);
        }

        return deduped;
    }

    @Nullable
    private String extractAttachmentUrl(Object attachment) {
        if (attachment instanceof String attachmentUrl) {
            return attachmentUrl;
        }
        if (attachment instanceof Map<?, ?> mapAttachment) {
            Object url = mapAttachment.get("url");
            return url != null ? url.toString() : null;
        }
        return null;
    }

    private boolean isLikelyAttachmentField(String key, String value) {
        if (key == null || key.isBlank() || !isAttachableUrl(value)) {
            return false;
        }

        String normalizedKey = key
            .replaceAll("([a-z])([A-Z])", "$1 $2")
            .replace('_', ' ')
            .replace('-', ' ')
            .toLowerCase(Locale.ROOT);

        for (String token : normalizedKey.split("\\s+")) {
            if (token.equals("attachment") || token.equals("attachments")
                || token.equals("upload") || token.equals("uploads")
                || token.equals("file") || token.equals("files")) {
                return true;
            }
        }
        return false;
    }

    private boolean isAttachableUrl(String value) {
        return isHttpUrl(value) || urlResolver.keyOf(value).isPresent();
    }

    private boolean isHttpUrl(String value) {
        return value != null
            && (value.regionMatches(true, 0, "https://", 0, 8)
                || value.regionMatches(true, 0, "http://", 0, 7));
    }

    private Map<String, Object> createAttachmentFromUrl(String url) {
        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("url", url);
        attachment.put("fileName", extractFileName(url));
        attachment.put("fileType", inferFileType(url));
        attachment.put("fileSize", 0);
        return attachment;
    }

    private String extractFileName(String url) {
        if (url == null || url.isBlank()) {
            return "attachment";
        }

        String cleanedUrl = url.split("\\?")[0];
        int lastSlashIndex = cleanedUrl.lastIndexOf('/');
        if (lastSlashIndex >= 0 && lastSlashIndex < cleanedUrl.length() - 1) {
            return cleanedUrl.substring(lastSlashIndex + 1);
        }
        return "attachment";
    }

    private String inferFileType(String url) {
        String fileName = extractFileName(url).toLowerCase(Locale.ROOT);
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "application/octet-stream";
        }

        String extension = fileName.substring(dotIndex + 1);
        return switch (extension) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "mp4" -> "video/mp4";
            case "webm" -> "video/webm";
            case "mov" -> "video/quicktime";
            case "mkv" -> "video/x-matroska";
            case "pdf" -> "application/pdf";
            default -> "application/octet-stream";
        };
    }

    public record FormDataProcessingResult(Map<String, Object> formData, List<Object> attachments) {}
}
