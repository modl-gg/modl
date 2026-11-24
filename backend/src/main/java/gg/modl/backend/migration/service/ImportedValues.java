package gg.modl.backend.migration.service;

import gg.modl.backend.migration.validation.MigrationValidator;
import org.jspecify.annotations.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ImportedValues {
    private final MigrationValidator validator;

    ImportedValues(MigrationValidator validator) {
        this.validator = validator;
    }

    @Nullable
    String text(@Nullable Object value, int maxCodePoints) {
        return value instanceof String text ? validator.sanitizeString(text, maxCodePoints) : null;
    }

    @Nullable
    String label(@Nullable Object value, int maxCodePoints) {
        String text = text(value, maxCodePoints);
        return text == null || text.isEmpty() ? null : text;
    }

    @Nullable
    String identifier(@Nullable Object value, int maxCodePoints) {
        String identifier = label(value, Integer.MAX_VALUE);
        return identifier != null && identifier.codePointCount(0, identifier.length()) <= maxCodePoints ? identifier : null;
    }

    @Nullable
    static Long wholeNumber(@Nullable Object value) {
        if (value instanceof Long || value instanceof Integer) {
            return ((Number) value).longValue();
        }
        return value instanceof Number number ? (long) number.doubleValue() : null;
    }

    static List<?> items(@Nullable Object value) {
        return value instanceof List<?> items ? items : List.of();
    }

    static Map<String, Object> entries(@Nullable Object value) {
        Map<String, Object> entries = new HashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, entry) -> {
                if (key instanceof String name) {
                    entries.put(name, entry);
                }
            });
        }
        return entries;
    }

    static void putOrRemove(Map<String, Object> data, String key, @Nullable Object value) {
        if (value == null) {
            data.remove(key);
        } else {
            data.put(key, value);
        }
    }
}
