package gg.modl.backend.hostedimport.archive;

import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class ArchiveDocument {
    private static final Instant EARLIEST = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant LATEST = Instant.parse("9999-12-31T23:59:59.999Z");
    private static final String ID = "_id";
    private static final String ALTERNATE_ID = "id";

    private final Map<String, @Nullable Object> fields;

    private ArchiveDocument(Map<String, @Nullable Object> fields) {
        this.fields = fields;
    }

    public static ArchiveDocument of(Map<String, @Nullable Object> fields) {
        return new ArchiveDocument(fields);
    }

    public static Optional<ArchiveDocument> of(@Nullable Object value) {
        return value instanceof Map<?, ?> map ? Optional.of(new ArchiveDocument(stringKeyed(map))) : Optional.empty();
    }

    public Map<String, @Nullable Object> fields() {
        return fields;
    }

    public boolean has(String key) {
        return fields.containsKey(key);
    }

    @Nullable
    public Object raw(String key) {
        return fields.get(key);
    }

    @Nullable
    public String string(String key) {
        return fields.get(key) instanceof String value ? value : null;
    }

    @Nullable
    public String text(String key) {
        Object value = fields.get(key);
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof ObjectIdValue) {
            return value.toString();
        }
        return null;
    }

    @Nullable
    public String id() {
        String id = idString(fields.get(ID));
        return id != null ? id : idString(fields.get(ALTERNATE_ID));
    }

    @Nullable
    public String idOf(String key) {
        return idString(fields.get(key));
    }

    @Nullable
    public static String idString(@Nullable Object value) {
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof ObjectIdValue objectId) {
            return objectId.hex();
        }
        if (value instanceof Integer || value instanceof Long) {
            return value.toString();
        }
        if (value instanceof BinaryValue binary) {
            return binary.asUuid().map(UUID::toString).orElse(null);
        }
        return null;
    }

    @Nullable
    public Instant instant(String key) {
        return instantOf(fields.get(key));
    }

    @Nullable
    public static Instant instantOf(@Nullable Object value) {
        Instant instant = null;
        if (value instanceof Instant date) {
            instant = date;
        } else if (value instanceof Long millis) {
            instant = Instant.ofEpochMilli(millis);
        } else if (value instanceof Integer millis) {
            instant = Instant.ofEpochMilli(millis);
        } else if (value instanceof Double millis && Double.isFinite(millis)) {
            instant = Instant.ofEpochMilli(millis.longValue());
        } else if (value instanceof String text) {
            instant = parse(text);
        }
        return instant == null || instant.isBefore(EARLIEST) || instant.isAfter(LATEST) ? null : instant;
    }

    @Nullable
    private static Instant parse(String text) {
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    @Nullable
    public Long longValue(String key) {
        return longOf(fields.get(key));
    }

    @Nullable
    public static Long longOf(@Nullable Object value) {
        if (value instanceof Integer number) {
            return number.longValue();
        }
        if (value instanceof Long number) {
            return number;
        }
        if (value instanceof Double number) {
            if (Double.isNaN(number)) {
                return null;
            }
            if (number >= Long.MAX_VALUE) {
                return Long.MAX_VALUE;
            }
            if (number <= Long.MIN_VALUE) {
                return Long.MIN_VALUE;
            }
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    @Nullable
    public Integer intValue(String key) {
        Long value = longValue(key);
        return value == null || value > Integer.MAX_VALUE || value < Integer.MIN_VALUE ? null : value.intValue();
    }

    public boolean bool(String key) {
        return Boolean.TRUE.equals(boolOrNull(key));
    }

    @Nullable
    public Boolean boolOrNull(String key) {
        Object value = fields.get(key);
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text) {
            String lower = text.trim().toLowerCase(Locale.ROOT);
            return "true".equals(lower) ? Boolean.TRUE : "false".equals(lower) ? Boolean.FALSE : null;
        }
        return null;
    }

    @Nullable
    public UUID uuid(String key) {
        return uuidOf(fields.get(key));
    }

    @Nullable
    public static UUID uuidOf(@Nullable Object value) {
        if (value instanceof BinaryValue binary) {
            return binary.asUuid().orElse(null);
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            try {
                UUID uuid = UUID.fromString(trimmed.length() == 32 ? dashed(trimmed) : trimmed);
                return uuid.toString().equalsIgnoreCase(trimmed.length() == 32 ? dashed(trimmed) : trimmed) ? uuid : null;
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    private static String dashed(String compact) {
        return compact.substring(0, 8) + "-" + compact.substring(8, 12) + "-" + compact.substring(12, 16) + "-"
            + compact.substring(16, 20) + "-" + compact.substring(20);
    }

    public Optional<ArchiveDocument> document(String key) {
        return of(fields.get(key));
    }

    public List<@Nullable Object> list(String key) {
        return fields.get(key) instanceof List<?> list ? Collections.unmodifiableList(new ArrayList<@Nullable Object>(list)) : List.of();
    }

    public List<ArchiveDocument> documents(String key) {
        return list(key).stream().map(ArchiveDocument::of).flatMap(Optional::stream).toList();
    }

    private static Map<String, @Nullable Object> stringKeyed(Map<?, ?> map) {
        Map<String, @Nullable Object> fields = new LinkedHashMap<>();
        map.forEach((key, value) -> fields.put(String.valueOf(key), value));
        return fields;
    }
}
