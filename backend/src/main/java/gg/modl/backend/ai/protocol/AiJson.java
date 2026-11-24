package gg.modl.backend.ai.protocol;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

final class AiJson {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private AiJson() {
    }

    static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    static JsonNode tree(Object value) {
        return MAPPER.valueToTree(value);
    }

    static String write(JsonNode node) {
        return MAPPER.writeValueAsString(node);
    }

    static Optional<JsonNode> parse(String body) {
        if (body.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(MAPPER.readTree(body));
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }

    @Nullable
    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.stringValue() : null;
    }

    @Nullable
    static String scalar(JsonNode node) {
        if (node.isString()) {
            return node.stringValue();
        }
        if (node.isNumber() || node.isBoolean()) {
            return node.asString();
        }
        return null;
    }

    static boolean arrayContains(JsonNode array, String value) {
        if (!array.isArray()) {
            return false;
        }
        for (JsonNode element : array.values()) {
            if (element.isString() && element.stringValue().equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    static Optional<Instant> instant(JsonNode node) {
        if (node.isIntegralNumber()) {
            return Optional.of(Instant.ofEpochSecond(node.longValue()));
        }
        if (!node.isString() || node.stringValue().isBlank()) {
            return Optional.empty();
        }
        String value = node.stringValue();
        try {
            return Optional.of(Instant.parse(value));
        } catch (DateTimeParseException notAnInstant) {
            try {
                return Optional.of(LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant());
            } catch (DateTimeParseException notADate) {
                return Optional.empty();
            }
        }
    }
}
