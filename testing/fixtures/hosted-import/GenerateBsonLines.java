import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.bson.BsonArray;
import org.bson.BsonBinary;
import org.bson.BsonBinarySubType;
import org.bson.BsonBinaryWriter;
import org.bson.BsonBoolean;
import org.bson.BsonDateTime;
import org.bson.BsonDbPointer;
import org.bson.BsonDecimal128;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonJavaScript;
import org.bson.BsonJavaScriptWithScope;
import org.bson.BsonMaxKey;
import org.bson.BsonMinKey;
import org.bson.BsonNull;
import org.bson.BsonObjectId;
import org.bson.BsonRegularExpression;
import org.bson.BsonString;
import org.bson.BsonSymbol;
import org.bson.BsonTimestamp;
import org.bson.BsonUndefined;
import org.bson.UuidRepresentation;
import org.bson.codecs.BsonDocumentCodec;
import org.bson.codecs.EncoderContext;
import org.bson.io.BasicOutputBuffer;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriter;
import org.bson.json.JsonWriterSettings;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;

final class GenerateBsonLines {
    private static final String BACKEND_GOLDENS = "backend/src/test/resources/golden/hosted-import";
    private static final String HARNESS_FIXTURE = "testing/fixtures/hosted-import";
    private static final UUID PLAYER = UUID.fromString("0b7e8a3c-6c55-4b3a-9d8a-111111111111");
    private static final JsonWriterSettings EXPORTER_SETTINGS = JsonWriterSettings.builder()
        .outputMode(JsonMode.RELAXED)
        .int64Converter((value, writer) -> {
            writer.writeStartObject();
            writer.writeString("$numberLong", Long.toString(value));
            writer.writeEndObject();
        })
        .build();

    private GenerateBsonLines() {
    }

    public static void main(String[] arguments) throws IOException {
        Path repository = Path.of(arguments[0]);
        Path goldens = repository.resolve(BACKEND_GOLDENS);
        List<BsonDocument> representable = List.of(numbers(), decimals(), times(), binaries(), special(), text(), player());
        write(goldens.resolve("form-a.ndjson"), representable, GenerateBsonLines::formA);
        write(goldens.resolve("form-b.ndjson"), representable, GenerateBsonLines::formB);
        write(goldens.resolve("form-b-only.ndjson"), List.of(reservedKeys(), databasePointer()), GenerateBsonLines::formB);
        write(repository.resolve(HARNESS_FIXTURE).resolve("form-b-audit.ndjson"), List.of(auditWithReservedKeys()), GenerateBsonLines::formB);
    }

    private static BsonDocument numbers() {
        return new BsonDocument("_id", new BsonObjectId(new ObjectId("665f1c2e8b3e4a0012345678")))
            .append("int32", new BsonInt32(5))
            .append("int32Min", new BsonInt32(Integer.MIN_VALUE))
            .append("int64", new BsonInt64(5))
            .append("int64Beyond53Bits", new BsonInt64(9007199254740993L))
            .append("int64Max", new BsonInt64(Long.MAX_VALUE))
            .append("wholeDouble", new BsonDouble(1.0))
            .append("largeDouble", new BsonDouble(1.5E20))
            .append("notANumber", new BsonDouble(Double.NaN))
            .append("infinity", new BsonDouble(Double.POSITIVE_INFINITY))
            .append("negativeInfinity", new BsonDouble(Double.NEGATIVE_INFINITY))
            .append("yes", BsonBoolean.TRUE)
            .append("no", BsonBoolean.FALSE)
            .append("nothing", BsonNull.VALUE);
    }

    private static BsonDocument decimals() {
        BsonDocument document = new BsonDocument("_id", new BsonString("decimals"));
        List<String> values = List.of("1.25", "-0", "0.000000", "0E+3", "-7.5E-10", "1E+3", "123456789012345678901234567890.1234",
            "9.999999999999999999999999999999999E+6144", "NaN", "Infinity", "-Infinity");
        for (int index = 0; index < values.size(); index++) {
            document.append("d" + index, new BsonDecimal128(Decimal128.parse(values.get(index))));
        }
        return document;
    }

    private static BsonDocument times() {
        return new BsonDocument("_id", new BsonString("times"))
            .append("millis", date("2026-11-22T10:15:30.123Z"))
            .append("whole", date("2026-11-22T10:15:30Z"))
            .append("beforeEpoch", date("1960-01-01T00:00:00Z"))
            .append("year10000", new BsonDateTime(253402300800000L))
            .append("timestamp", new BsonTimestamp(1732270000, 2));
    }

    private static BsonDocument binaries() {
        return new BsonDocument("_id", new BsonString("binaries"))
            .append("standardUuid", new BsonBinary(PLAYER, UuidRepresentation.STANDARD))
            .append("javaLegacyUuid", new BsonBinary(PLAYER, UuidRepresentation.JAVA_LEGACY))
            .append("generic", new BsonBinary(new byte[] {0, 1, 2, (byte) 0xFF}))
            .append("empty", new BsonBinary(new byte[0]))
            .append("userDefined", new BsonBinary(BsonBinarySubType.USER_DEFINED, new byte[] {7}));
    }

    private static BsonDocument special() {
        return new BsonDocument("_id", new BsonString("special"))
            .append("regex", new BsonRegularExpression("^/login\\s", "i"))
            .append("minKey", new BsonMinKey())
            .append("maxKey", new BsonMaxKey())
            .append("undefined", new BsonUndefined())
            .append("symbol", new BsonSymbol("symbol"))
            .append("code", new BsonJavaScript("return 1;"))
            .append("codeWithScope", new BsonJavaScriptWithScope("return x;", new BsonDocument("x", new BsonInt32(1))));
    }

    private static BsonDocument text() {
        return new BsonDocument("_id", new BsonString("text"))
            .append("unicode", new BsonString("Zoë 漢字 🙂"))
            .append("escapes", new BsonString("quote \" backslash \\ line\nbreak\ttab"))
            .append("withNul", new BsonString("a\u0000b"))
            .append("_class", new BsonString("gg.modl.Hidden"))
            .append("nested", new BsonDocument("_class", new BsonString("x")).append("keep", BsonBoolean.TRUE))
            .append("emptyDocument", new BsonDocument())
            .append("emptyArray", new BsonArray())
            .append("arrays", new BsonArray(List.of(new BsonArray(List.of(new BsonInt32(1), new BsonString("two"))), new BsonDocument())));
    }

    private static BsonDocument player() {
        BsonArray logins = new BsonArray(List.of(date("2026-01-02T00:00:00Z"), date("2026-01-01T00:00:00Z"), date("2026-01-03T00:00:00Z")));
        return new BsonDocument("_id", new BsonString("p1"))
            .append("minecraftUuid", new BsonBinary(PLAYER, UuidRepresentation.JAVA_LEGACY))
            .append("ipAddresses", new BsonArray(List.of(new BsonDocument("ipAddress", new BsonString("198.51.100.4"))
                .append("logins", logins))))
            .append("punishments", new BsonArray(List.of(new BsonDocument("id", new BsonString("ban-1"))
                .append("typeOrdinal", new BsonInt32(2))
                .append("data", new BsonDocument("duration", new BsonInt64(-1))))));
    }

    private static BsonDocument reservedKeys() {
        return new BsonDocument("_id", new BsonObjectId(new ObjectId("665f1c2e8b3e4a0012345679")))
            .append("formData", new BsonDocument("$where", new BsonString("1 == 1"))
                .append("$set", new BsonDocument("$oid", new BsonString("not an id"))));
    }

    private static BsonDocument databasePointer() {
        return new BsonDocument("_id", new BsonString("pointer"))
            .append("pointer", new BsonDbPointer("tenant.players", new ObjectId("665f1c2e8b3e4a001234567a")));
    }

    private static BsonDocument auditWithReservedKeys() {
        return new BsonDocument("_id", new BsonObjectId(new ObjectId("665f1c2e8b3e4a001234567b")))
            .append("level", new BsonString("info"))
            .append("source", new BsonString("hosted-fixture"))
            .append("description", new BsonString("raw BSON audit entry"))
            .append("metadata", new BsonDocument("$where", new BsonString("1 == 1")));
    }

    private static BsonDateTime date(String instant) {
        return new BsonDateTime(Instant.parse(instant).toEpochMilli());
    }

    private static String formA(BsonDocument document) {
        StringWriter text = new StringWriter();
        new BsonDocumentCodec().encode(new JsonWriter(text, EXPORTER_SETTINGS), document, EncoderContext.builder().build());
        return text.toString();
    }

    private static String formB(BsonDocument document) {
        try (BasicOutputBuffer bson = new BasicOutputBuffer(); BsonBinaryWriter writer = new BsonBinaryWriter(bson)) {
            new BsonDocumentCodec().encode(writer, document, EncoderContext.builder().build());
            return "{\"$modlBson\": \"" + Base64.getEncoder().encodeToString(bson.toByteArray()) + "\"}";
        }
    }

    private static void write(Path file, List<BsonDocument> documents, Function<BsonDocument, String> form) throws IOException {
        StringBuilder lines = new StringBuilder();
        documents.forEach(document -> lines.append(form.apply(document)).append('\n'));
        Files.createDirectories(file.getParent());
        Files.writeString(file, lines);
    }
}
