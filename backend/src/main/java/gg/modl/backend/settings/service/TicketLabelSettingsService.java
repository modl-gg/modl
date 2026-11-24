package gg.modl.backend.settings.service;

import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.Label;
import gg.modl.backend.settings.data.SettingsType;
import gg.modl.backend.settings.data.TicketLabelSettings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;

@Service
@Slf4j
public class TicketLabelSettingsService {
    private static final int MAX_LABELS = 100;
    private static final int MAX_LABEL_ID_LENGTH = 64;
    private static final int MAX_LABEL_NAME_LENGTH = 64;
    private static final int MAX_LABEL_DESCRIPTION_LENGTH = 512;
    private static final String DEFAULT_LABEL_COLOR = "#6b7280";
    private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{3})$");

    private final VersionedSettingsSupport<TicketLabelSettings> support;

    public TicketLabelSettingsService(SettingsDocumentService settingsDocumentService) {
        this.support = VersionedSettingsSupport.of(
            settingsDocumentService, SettingsType.TICKET_LABELS, this::mapToTicketLabelSettings);
    }

    public TicketLabelSettings getTicketLabelSettings(Server server) {
        return support.get(server);
    }

    public VersionedSettings<TicketLabelSettings> getTicketLabelSettingsState(Server server) {
        return support.state(server);
    }

    public VersionedSettings<TicketLabelSettings> patchTicketLabelSettings(
        Server server,
        long expectedVersion,
        List<Label> labels
    ) {
        return support.update(server, expectedVersion, data -> {
            if (labels != null) {
                List<Map<String, Object>> normalizedLabels = normalizeLabels(labels).stream()
                    .map(this::labelToMap)
                    .collect(Collectors.toCollection(ArrayList::new));
                data.put("labels", normalizedLabels);
            }
            return data;
        });
    }

    @SuppressWarnings("unchecked")
    private TicketLabelSettings mapToTicketLabelSettings(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return TicketLabelSettings.builder().labels(new ArrayList<>()).build();
        }

        Object rawLabels = data.get("labels");
        if (!(rawLabels instanceof List<?> labelsList)) {
            return TicketLabelSettings.builder().labels(new ArrayList<>()).build();
        }

        List<Label> labels = new ArrayList<>();
        for (Object rawLabel : labelsList) {
            if (rawLabel instanceof Map<?, ?> labelMap) {
                labels.add(mapToLabel((Map<String, Object>) labelMap));
                continue;
            }
            try {
                labels.add(mapToLabel(SettingsCodec.toDocument(rawLabel)));
            } catch (JacksonException exception) {
                log.warn("Skipping malformed ticket label entry: {}", exception.getMessage());
            }
        }

        return TicketLabelSettings.builder()
            .labels(normalizeLabels(labels))
            .build();
    }

    private List<Label> normalizeLabels(List<Label> labels) {
        if (labels == null) {
            return new ArrayList<>();
        }

        List<Label> normalized = labels.stream()
            .filter(Objects::nonNull)
            .map(this::normalizeLabel)
            .filter(label -> label.getName() != null && !label.getName().isBlank())
            .collect(Collectors.toCollection(ArrayList::new));

        LinkedHashSet<String> seenNames = new LinkedHashSet<>();
        List<Label> deduped = new ArrayList<>();
        for (Label label : normalized) {
            String key = label.getName().toLowerCase(Locale.ROOT);
            if (seenNames.contains(key)) {
                continue;
            }
            seenNames.add(key);
            deduped.add(label);
        }

        LinkedHashSet<String> seenIds = new LinkedHashSet<>();
        List<Label> normalizedIds = new ArrayList<>();
        for (Label label : deduped) {
            String resolvedId = label.getId();
            while (resolvedId == null || resolvedId.isBlank() || seenIds.contains(resolvedId)) {
                resolvedId = UUID.randomUUID().toString();
            }
            seenIds.add(resolvedId);

            normalizedIds.add(Label.builder()
                .id(resolvedId)
                .name(label.getName())
                .color(label.getColor())
                .description(label.getDescription())
                .build());
        }

        if (normalizedIds.size() > MAX_LABELS) {
            return new ArrayList<>(normalizedIds.subList(0, MAX_LABELS));
        }

        return normalizedIds;
    }

    private Label normalizeLabel(Label label) {
        return normalizeLabel(label.getId(), label.getName(), label.getColor(), label.getDescription());
    }

    private Label normalizeLabel(@Nullable String id, @Nullable String name, @Nullable String color, @Nullable String description) {
        String normalizedId = sanitizeId(id);
        if (normalizedId == null || normalizedId.isBlank()) {
            normalizedId = UUID.randomUUID().toString();
        }

        String normalizedColor = color != null && !color.isBlank() ? color.trim() : DEFAULT_LABEL_COLOR;
        if (!HEX_COLOR_PATTERN.matcher(normalizedColor).matches()) {
            normalizedColor = DEFAULT_LABEL_COLOR;
        }
        normalizedColor = normalizedColor.toLowerCase(Locale.ROOT);

        String normalizedName = truncate(name != null ? name.trim() : "", MAX_LABEL_NAME_LENGTH);
        String normalizedDescription = description != null
                                       ? truncate(description.trim(), MAX_LABEL_DESCRIPTION_LENGTH)
                                       : null;

        return Label.builder()
            .id(normalizedId)
            .name(normalizedName)
            .color(normalizedColor)
            .description(normalizedDescription)
            .build();
    }

    private @Nullable String sanitizeId(@Nullable String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        if (trimmed.length() <= MAX_LABEL_ID_LENGTH) {
            return trimmed;
        }

        return trimmed.substring(0, MAX_LABEL_ID_LENGTH);
    }

    private Label mapToLabel(Map<String, Object> map) {
        return normalizeLabel(textOf(map, "id"), textOf(map, "name"), textOf(map, "color"), textOf(map, "description"));
    }

    private static @Nullable String textOf(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private Map<String, Object> labelToMap(Label label) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", label.getId());
        map.put("name", label.getName());
        map.put("color", label.getColor());
        if (label.getDescription() != null) {
            map.put("description", label.getDescription());
        }
        return map;
    }

    private String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
