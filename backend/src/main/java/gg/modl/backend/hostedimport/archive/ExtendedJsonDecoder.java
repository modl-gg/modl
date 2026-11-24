package gg.modl.backend.hostedimport.archive;

import java.io.ByteArrayOutputStream;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.Base64Variants;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;

public final class ExtendedJsonDecoder {
    static final String CLASS_HINT = "_class";
    private static final String FORM_B_KEY = "$modlBson";
    private static final Pattern OBJECT_ID = Pattern.compile("[0-9a-fA-F]{24}");
    private static final JsonMapper MAPPER = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                .maxStringLength(ArchiveLimits.MAX_LINE_BYTES)
                .maxNestingDepth(ArchiveLimits.MAX_DEPTH + 8)
                .build())
            .build())
        .build();

    private ExtendedJsonDecoder() {
    }

    public static Map<String, @Nullable Object> decodeLine(byte[] line, int length) {
        DocumentBudget budget = new DocumentBudget(ArchiveLimits.MAX_DOCUMENT_HEAP_BYTES);
        try (JsonParser parser = MAPPER.createParser(line, 0, length)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw unreadable("not an object");
            }
            JsonToken first = parser.nextToken();
            Map<String, @Nullable Object> document;
            if (first == JsonToken.PROPERTY_NAME && FORM_B_KEY.equals(parser.currentName())) {
                document = RawBsonDecoder.decode(formBBytes(parser), budget);
            } else {
                document = objectAfterStart(parser, budget, first);
            }
            if (parser.nextToken() != null) {
                throw unreadable("trailing content");
            }
            return document;
        } catch (JacksonException | IllegalArgumentException | DateTimeException e) {
            throw unreadable("invalid JSON");
        }
    }

    private static byte[] formBBytes(JsonParser parser) {
        if (parser.nextToken() != JsonToken.VALUE_STRING) {
            throw unreadable("form B value is not a string");
        }
        ByteArrayOutputStream bytes = new BoundedBytes(ArchiveLimits.MAX_BSON_DOCUMENT_BYTES);
        parser.readBinaryValue(Base64Variants.MIME_NO_LINEFEEDS, bytes);
        if (parser.nextToken() != JsonToken.END_OBJECT) {
            throw unreadable("form B line has more keys");
        }
        return bytes.toByteArray();
    }

    private static Map<String, @Nullable Object> objectAfterStart(JsonParser parser, DocumentBudget budget, JsonToken first) {
        if (first == JsonToken.END_OBJECT) {
            return new LinkedHashMap<>();
        }
        String name = parser.currentName();
        if (name.startsWith("$")) {
            throw unreadable("type wrapper where a document was expected");
        }
        return fields(parser, budget, name);
    }

    private static @Nullable Object value(JsonParser parser, DocumentBudget budget) {
        budget.node();
        return switch (parser.currentToken()) {
            case START_OBJECT -> object(parser, budget);
            case START_ARRAY -> array(parser, budget);
            case VALUE_STRING -> budget.string(parser.getString());
            case VALUE_NUMBER_INT -> switch (parser.getNumberType()) {
                case INT -> parser.getIntValue();
                case LONG -> parser.getLongValue();
                default -> throw unreadable("integer out of range");
            };
            case VALUE_NUMBER_FLOAT -> parser.getDoubleValue();
            case VALUE_TRUE -> true;
            case VALUE_FALSE -> false;
            case VALUE_NULL -> null;
            default -> throw unreadable("unexpected token");
        };
    }

    private static @Nullable Object object(JsonParser parser, DocumentBudget budget) {
        JsonToken token = parser.nextToken();
        if (token == JsonToken.END_OBJECT) {
            return new LinkedHashMap<String, @Nullable Object>();
        }
        String name = parser.currentName();
        if (name.startsWith("$")) {
            return wrapper(parser, name, budget);
        }
        return fields(parser, budget, name);
    }

    private static Map<String, @Nullable Object> fields(JsonParser parser, DocumentBudget budget, String firstName) {
        Map<String, @Nullable Object> fields = new LinkedHashMap<>();
        String name = firstName;
        while (true) {
            parser.nextToken();
            String key = budget.string(name);
            budget.enter(key);
            Object value = value(parser, budget);
            budget.leave();
            if (!CLASS_HINT.equals(key)) {
                fields.put(key, value);
            }
            JsonToken next = parser.nextToken();
            if (next == JsonToken.END_OBJECT) {
                return fields;
            }
            if (next != JsonToken.PROPERTY_NAME) {
                throw unreadable("malformed object");
            }
            name = parser.currentName();
            if (name.startsWith("$")) {
                throw unreadable("key starting with $ in form A");
            }
        }
    }

    private static Object array(JsonParser parser, DocumentBudget budget) {
        boolean fold = budget.foldsDates();
        budget.enterElement();
        Object array = fold ? foldedDates(parser, budget) : items(parser, budget);
        budget.leave();
        return array;
    }

    private static FoldedDates foldedDates(JsonParser parser, DocumentBudget budget) {
        FoldedDates folded = FoldedDates.EMPTY;
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (value(parser, budget) instanceof Instant date) {
                folded = folded.with(date);
            }
        }
        return folded;
    }

    private static List<@Nullable Object> items(JsonParser parser, DocumentBudget budget) {
        List<@Nullable Object> items = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            items.add(value(parser, budget));
        }
        return items;
    }

    private static @Nullable Object wrapper(JsonParser parser, String name, DocumentBudget budget) {
        parser.nextToken();
        @Nullable Object value = switch (name) {
            case "$oid" -> objectId(text(parser));
            case "$numberLong" -> parseLong(text(parser));
            case "$numberInt" -> parseInt(text(parser));
            case "$numberDouble" -> parseDouble(text(parser));
            case "$numberDecimal" -> budget.string(text(parser));
            case "$date" -> date(parser);
            case "$binary" -> binary(parser, budget);
            case "$uuid" -> uuid(text(parser));
            case "$regularExpression" -> pair(parser, budget, "pattern", "options");
            case "$timestamp" -> timestamp(pair(parser, budget, "t", "i"));
            case "$minKey" -> skipValue(parser, "MinKey");
            case "$maxKey" -> skipValue(parser, "MaxKey");
            case "$undefined" -> skipValue(parser, null);
            case "$symbol" -> budget.string(text(parser));
            case "$code" -> code(parser, budget);
            default -> throw unreadable("unknown type wrapper");
        };
        if (!"$code".equals(name) && parser.nextToken() != JsonToken.END_OBJECT) {
            throw unreadable("type wrapper has extra keys");
        }
        return value;
    }

    private static Object code(JsonParser parser, DocumentBudget budget) {
        String code = budget.string(text(parser));
        JsonToken next = parser.nextToken();
        if (next == JsonToken.END_OBJECT) {
            return code;
        }
        if (next != JsonToken.PROPERTY_NAME || !"$scope".equals(parser.currentName())) {
            throw unreadable("malformed code");
        }
        parser.nextToken();
        @Nullable Object scope = value(parser, budget);
        if (parser.nextToken() != JsonToken.END_OBJECT) {
            throw unreadable("malformed code");
        }
        Map<String, @Nullable Object> withScope = new LinkedHashMap<>();
        withScope.put("code", code);
        withScope.put("scope", scope);
        return withScope;
    }

    private static @Nullable Object skipValue(JsonParser parser, @Nullable Object replacement) {
        parser.skipChildren();
        return replacement;
    }

    private static Map<String, @Nullable Object> pair(JsonParser parser, DocumentBudget budget, String first, String second) {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            throw unreadable("malformed wrapper");
        }
        Map<String, @Nullable Object> values = new LinkedHashMap<>();
        while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
            String key = parser.currentName();
            parser.nextToken();
            if (!key.equals(first) && !key.equals(second)) {
                throw unreadable("malformed wrapper");
            }
            values.put(key, value(parser, budget));
        }
        return values;
    }

    private static Map<String, @Nullable Object> timestamp(Map<String, @Nullable Object> parts) {
        parts.replaceAll((part, value) -> {
            if (value instanceof Integer || value instanceof Long) {
                return ((Number) value).longValue();
            }
            throw unreadable("malformed timestamp");
        });
        return parts;
    }

    private static Object date(JsonParser parser) {
        JsonToken token = parser.currentToken();
        if (token == JsonToken.VALUE_STRING) {
            try {
                return OffsetDateTime.parse(parser.getString()).toInstant();
            } catch (DateTimeParseException e) {
                throw unreadable("malformed date");
            }
        }
        if (token == JsonToken.VALUE_NUMBER_INT) {
            return Instant.ofEpochMilli(parser.getLongValue());
        }
        if (token == JsonToken.START_OBJECT && parser.nextToken() == JsonToken.PROPERTY_NAME
            && "$numberLong".equals(parser.currentName())) {
            parser.nextToken();
            long millis = parseLong(text(parser));
            if (parser.nextToken() != JsonToken.END_OBJECT) {
                throw unreadable("malformed date");
            }
            return Instant.ofEpochMilli(millis);
        }
        throw unreadable("malformed date");
    }

    private static BinaryValue binary(JsonParser parser, DocumentBudget budget) {
        if (parser.currentToken() == JsonToken.VALUE_STRING) {
            byte[] data = base64(parser.getString());
            if (parser.nextToken() != JsonToken.PROPERTY_NAME || !"$type".equals(parser.currentName())) {
                throw unreadable("malformed binary");
            }
            parser.nextToken();
            budget.bytes(data.length);
            return new BinaryValue(Integer.parseInt(text(parser), 16), data);
        }
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            throw unreadable("malformed binary");
        }
        byte[] data = null;
        Integer subtype = null;
        while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
            String key = parser.currentName();
            parser.nextToken();
            switch (key) {
                case "base64" -> data = base64(text(parser));
                case "subType" -> subtype = parseHex(text(parser));
                default -> throw unreadable("malformed binary");
            }
        }
        if (data == null || subtype == null) {
            throw unreadable("malformed binary");
        }
        budget.bytes(data.length);
        return new BinaryValue(subtype, data);
    }

    private static BinaryValue uuid(String text) {
        try {
            UUID uuid = UUID.fromString(text);
            byte[] bytes = HexFormat.of().parseHex(uuid.toString().replace("-", ""));
            return new BinaryValue(4, bytes);
        } catch (IllegalArgumentException e) {
            throw unreadable("malformed uuid");
        }
    }

    private static String text(JsonParser parser) {
        if (parser.currentToken() != JsonToken.VALUE_STRING) {
            throw unreadable("expected a string");
        }
        return parser.getString();
    }

    private static ObjectIdValue objectId(String hex) {
        if (!OBJECT_ID.matcher(hex).matches()) {
            throw unreadable("malformed object id");
        }
        return new ObjectIdValue(hex);
    }

    private static long parseLong(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw unreadable("malformed long");
        }
    }

    private static int parseInt(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw unreadable("malformed int");
        }
    }

    private static int parseHex(String text) {
        try {
            int value = Integer.parseInt(text, 16);
            if (value < 0 || value > 0xff) {
                throw unreadable("malformed binary subtype");
            }
            return value;
        } catch (NumberFormatException e) {
            throw unreadable("malformed binary subtype");
        }
    }

    private static double parseDouble(String text) {
        return switch (text) {
            case "NaN" -> Double.NaN;
            case "Infinity" -> Double.POSITIVE_INFINITY;
            case "-Infinity" -> Double.NEGATIVE_INFINITY;
            default -> {
                try {
                    yield Double.parseDouble(text);
                } catch (NumberFormatException e) {
                    throw unreadable("malformed double");
                }
            }
        };
    }

    private static byte[] base64(String text) {
        try {
            return Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            throw unreadable("malformed base64");
        }
    }

    private static UnreadableDocumentException unreadable(String reason) {
        return new UnreadableDocumentException(reason, false);
    }

    private static final class BoundedBytes extends ByteArrayOutputStream {
        private final int limit;

        private BoundedBytes(int limit) {
            this.limit = limit;
        }

        @Override
        public synchronized void write(byte[] buffer, int offset, int length) {
            requireRoom(length);
            super.write(buffer, offset, length);
        }

        @Override
        public synchronized void write(int value) {
            requireRoom(1);
            super.write(value);
        }

        private void requireRoom(int length) {
            if (count + length > limit) {
                throw new UnreadableDocumentException("form B document too large", true);
            }
        }
    }
}
