package gg.modl.update;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.Map;

final class JsonFields {
    private final Map<String, Object> values;
    private final String source;

    JsonFields(@Nullable Map<String, Object> values, String source) {
        this.values = values == null ? Collections.<String, Object>emptyMap() : values;
        this.source = source;
    }

    boolean has(String name) {
        return values.get(name) != null;
    }

    String text(String name) throws InvalidJsonFieldException {
        Object value = values.get(name);
        if (!(value instanceof String)) {
            throw invalid(name);
        }
        return (String) value;
    }

    @Nullable String optionalText(String name) throws InvalidJsonFieldException {
        return has(name) ? text(name) : null;
    }

    long number(String name) throws InvalidJsonFieldException {
        Object value = values.get(name);
        if (!(value instanceof Number)) {
            throw invalid(name);
        }
        double asDouble = ((Number) value).doubleValue();
        long asLong = ((Number) value).longValue();
        if (asDouble != (double) asLong) {
            throw invalid(name);
        }
        return asLong;
    }

    int integer(String name) throws InvalidJsonFieldException {
        long value = number(name);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw invalid(name);
        }
        return (int) value;
    }

    boolean flag(String name) throws InvalidJsonFieldException {
        Object value = values.get(name);
        if (!(value instanceof Boolean)) {
            throw invalid(name);
        }
        return (Boolean) value;
    }

    @Nullable Boolean optionalFlag(String name) throws InvalidJsonFieldException {
        return has(name) ? flag(name) : null;
    }

    Instant instant(String name) throws InvalidJsonFieldException {
        try {
            return Instant.parse(text(name));
        } catch (DateTimeParseException e) {
            throw invalid(name);
        }
    }

    ReleaseVersion version(String name) throws InvalidJsonFieldException {
        ReleaseVersion version = ReleaseVersion.tryParse(text(name));
        if (version == null) {
            throw invalid(name);
        }
        return version;
    }

    @Nullable ReleaseVersion optionalVersion(String name) throws InvalidJsonFieldException {
        return has(name) ? version(name) : null;
    }

    @SuppressWarnings("unchecked")
    JsonFields object(String name) throws InvalidJsonFieldException {
        Object value = values.get(name);
        if (!(value instanceof Map)) {
            throw invalid(name);
        }
        return new JsonFields((Map<String, Object>) value, source + "." + name);
    }

    <E extends Enum<E>> E constant(String name, Class<E> type) throws InvalidJsonFieldException {
        String text = text(name);
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(text)) {
                return constant;
            }
        }
        throw invalid(name);
    }

    private InvalidJsonFieldException invalid(String name) {
        return new InvalidJsonFieldException(source + " has a missing or invalid " + name);
    }
}
