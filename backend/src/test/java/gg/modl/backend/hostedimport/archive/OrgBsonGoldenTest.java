package gg.modl.backend.hostedimport.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class OrgBsonGoldenTest {
    private static final Pattern RAW_BSON_LINE = Pattern.compile("\\{\"\\$modlBson\": \"([A-Za-z0-9+/=]+)\"}");
    private static final UUID PLAYER = UUID.fromString("0b7e8a3c-6c55-4b3a-9d8a-111111111111");
    private static final int NUMBERS = 0;
    private static final int DECIMALS = 1;
    private static final int TIMES = 2;
    private static final int BINARIES = 3;
    private static final int SPECIAL = 4;
    private static final int TEXT = 5;
    private static final int PLAYER_DOCUMENT = 6;

    @Test
    void bothLineFormsWrittenByOrgBsonDecodeToTheSameDocuments() throws IOException {
        List<String> extendedJson = golden("form-a.ndjson");
        List<String> rawBson = golden("form-b.ndjson");

        assertThat(rawBson).hasSameSizeAs(extendedJson).hasSize(PLAYER_DOCUMENT + 1);
        for (int line = 0; line < extendedJson.size(); line++) {
            assertThat(decode(rawBson.get(line))).as("line %s", line + 1).isEqualTo(decode(extendedJson.get(line)));
        }
    }

    @Test
    void everyBsonTypeDecodesToItsValue() throws IOException {
        List<String> lines = golden("form-b.ndjson");
        Map<String, Object> numbers = decode(lines.get(NUMBERS));
        Map<String, Object> decimals = decode(lines.get(DECIMALS));
        Map<String, Object> times = decode(lines.get(TIMES));
        Map<String, Object> binaries = decode(lines.get(BINARIES));
        Map<String, Object> special = decode(lines.get(SPECIAL));
        Map<String, Object> text = decode(lines.get(TEXT));
        Map<String, Object> player = decode(lines.get(PLAYER_DOCUMENT));

        assertThat(numbers).containsEntry("_id", new ObjectIdValue("665f1c2e8b3e4a0012345678"))
            .containsEntry("int32", 5).containsEntry("int64", 5L).containsEntry("int64Beyond53Bits", 9007199254740993L)
            .containsEntry("int64Max", Long.MAX_VALUE).containsEntry("wholeDouble", 1.0).containsEntry("notANumber", Double.NaN)
            .containsEntry("negativeInfinity", Double.NEGATIVE_INFINITY).containsEntry("nothing", null);
        assertThat(decimals.values()).containsExactly("decimals", "1.25", "-0", "0.000000", "0E+3", "-7.5E-10", "1E+3",
            "123456789012345678901234567890.1234", "9.999999999999999999999999999999999E+6144", "NaN", "Infinity", "-Infinity");
        assertThat(times).containsEntry("millis", Instant.parse("2026-11-22T10:15:30.123Z"))
            .containsEntry("beforeEpoch", Instant.parse("1960-01-01T00:00:00Z"))
            .containsEntry("year10000", Instant.ofEpochMilli(253402300800000L))
            .containsEntry("timestamp", Map.of("t", 1732270000L, "i", 2L));
        assertThat(ArchiveDocument.uuidOf(binaries.get("standardUuid"))).isEqualTo(PLAYER);
        assertThat(ArchiveDocument.uuidOf(binaries.get("javaLegacyUuid"))).isEqualTo(PLAYER);
        assertThat(binaries).containsEntry("userDefined", new BinaryValue(0x80, new byte[] {7}));
        assertThat(special).containsEntry("minKey", "MinKey").containsEntry("maxKey", "MaxKey").containsEntry("undefined", null)
            .containsEntry("symbol", "symbol").containsEntry("code", "return 1;")
            .containsEntry("codeWithScope", Map.of("code", "return x;", "scope", Map.of("x", 1)));
        assertThat(text).containsEntry("withNul", "ab").containsEntry("nested", Map.of("keep", true)).doesNotContainKey("_class");
        assertThat(ArchiveDocument.of(player).uuid("minecraftUuid")).isEqualTo(PLAYER);
        assertThat(ArchiveDocument.of(player).documents("ipAddresses").getFirst().raw("logins")).isEqualTo(new FoldedDates(
            Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-03T00:00:00Z"), 3));
    }

    @Test
    void reservedKeysAndDatabasePointersOfFormBArePlainData() throws IOException {
        List<String> lines = golden("form-b-only.ndjson");

        assertThat(decode(lines.getFirst()).get("formData"))
            .isEqualTo(Map.of("$where", "1 == 1", "$set", Map.of("$oid", "not an id")));
        assertThat(decode(lines.getLast()).get("pointer"))
            .isEqualTo(Map.of("ref", "tenant.players", "id", new ObjectIdValue("665f1c2e8b3e4a001234567a")));
    }

    @Test
    void everyTruncationOfARealBsonDocumentIsRefused() throws IOException {
        for (byte[] document : rawDocuments()) {
            for (int length = 0; length < document.length; length++) {
                byte[] truncated = Arrays.copyOf(document, length);
                assertThatThrownBy(() -> RawBsonDecoder.decode(truncated)).isInstanceOf(UnreadableDocumentException.class);
                if (length >= Integer.BYTES) {
                    ByteBuffer.wrap(truncated).order(ByteOrder.LITTLE_ENDIAN).putInt(0, length);
                    assertThatThrownBy(() -> RawBsonDecoder.decode(truncated)).isInstanceOf(UnreadableDocumentException.class);
                }
            }
        }
    }

    private static List<byte[]> rawDocuments() throws IOException {
        List<String> lines = new ArrayList<>(golden("form-b.ndjson"));
        lines.addAll(golden("form-b-only.ndjson"));
        List<byte[]> documents = new ArrayList<>();
        for (String line : lines) {
            Matcher matcher = RAW_BSON_LINE.matcher(line);
            assertThat(matcher.matches()).isTrue();
            documents.add(Base64.getDecoder().decode(matcher.group(1)));
        }
        return documents;
    }

    private static Map<String, Object> decode(String line) {
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        return ExtendedJsonDecoder.decodeLine(bytes, bytes.length);
    }

    private static List<String> golden(String name) throws IOException {
        try (InputStream input = Objects.requireNonNull(
            OrgBsonGoldenTest.class.getResourceAsStream("/golden/hosted-import/" + name), name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
    }
}
