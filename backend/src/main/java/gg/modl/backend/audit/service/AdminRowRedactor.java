package gg.modl.backend.audit.service;

import static gg.modl.backend.database.jooq.Tables.SETTINGS;

import gg.modl.backend.audit.repository.BrowsableTable;
import gg.modl.backend.database.JsonColumns;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

@RequiredArgsConstructor
final class AdminRowRedactor {
    static final String REDACTED = "[REDACTED]";

    private static final String JSON_SUFFIX = "_json";
    private static final Pattern KEY_SEPARATORS = Pattern.compile("[^a-z0-9]");
    private static final List<String> SECRET_MARKERS =
        List.of("apikey", "webhookurl", "token", "secret", "password", "publicaccesskey", "hash");
    private static final Set<String> SAFE_SETTINGS_TYPES = Set.of(
        "dataRetention", "general", "punishmentTypes", "quickResponses", "replayRetention",
        "statusThresholds", "ticketForms", "ticketLabels");

    private final JsonColumns jsonColumns;

    Map<String, Object> redact(BrowsableTable table, Map<String, Object> row) {
        boolean hidesSettingsData = table == BrowsableTable.SETTINGS && !isSafeSetting(row.get(SETTINGS.TYPE.getName()));
        Map<String, Object> redacted = new LinkedHashMap<>();
        row.forEach((column, value) -> {
            boolean hidden = isSecret(column) || (hidesSettingsData && SETTINGS.DATA_JSON.getName().equals(column));
            redacted.put(column, hidden ? REDACTED : renderColumn(column, value));
        });
        return redacted;
    }

    @Nullable
    private Object renderColumn(String column, @Nullable Object value) {
        if (value instanceof Instant instant) {
            return Long.toString(instant.toEpochMilli());
        }
        if (value instanceof Long number) {
            return number.toString();
        }
        if (value instanceof String json && column.endsWith(JSON_SUFFIX)) {
            return redactJson(jsonColumns.read(json, Object.class));
        }
        return value;
    }

    @Nullable
    private static Object redactJson(@Nullable Object value) {
        if (value instanceof Map<?, ?> object) {
            Map<String, Object> redacted = new LinkedHashMap<>();
            object.forEach((key, nested) -> {
                String name = String.valueOf(key);
                redacted.put(name, isSecret(name) ? REDACTED : redactJson(nested));
            });
            return redacted;
        }
        if (value instanceof List<?> list) {
            List<Object> redacted = new ArrayList<>(list.size());
            list.forEach(element -> redacted.add(redactJson(element)));
            return redacted;
        }
        return value;
    }

    private static boolean isSafeSetting(@Nullable Object type) {
        return type instanceof String name && SAFE_SETTINGS_TYPES.contains(name);
    }

    private static boolean isSecret(String key) {
        String normalized = KEY_SEPARATORS.matcher(key.toLowerCase(Locale.ROOT)).replaceAll("");
        return SECRET_MARKERS.stream().anyMatch(normalized::contains);
    }
}
