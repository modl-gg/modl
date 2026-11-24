package gg.modl.backend.hostedimport.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentDecodersTest {
    @Test
    void extendedJsonTypesDecodeToTypedValues() {
        Map<String, Object> document = decode("{\"_id\":{\"$oid\":\"665F1C2E8B3E4A0012345678\"},\"n\":5,\"l\":{\"$numberLong\":\"9007199254740993\"},"
            + "\"d\":1.0,\"nan\":{\"$numberDouble\":\"NaN\"},\"at\":{\"$date\":\"2026-11-22T10:15:30.123Z\"},"
            + "\"old\":{\"$date\":{\"$numberLong\":\"-1000\"}},\"u\":{\"$binary\":{\"base64\":\"AAECAwQFBgcICQoLDA0ODw==\",\"subType\":\"04\"}},"
            + "\"dec\":{\"$numberDecimal\":\"1.25\"},\"_class\":\"gg.Hidden\",\"nested\":{\"_class\":\"x\",\"keep\":true}}");

        assertThat(document.get("_id")).isEqualTo(new ObjectIdValue("665f1c2e8b3e4a0012345678"));
        assertThat(document.get("n")).isEqualTo(5);
        assertThat(document.get("l")).isEqualTo(9007199254740993L);
        assertThat(document.get("d")).isEqualTo(1.0);
        assertThat((Double) document.get("nan")).isNaN();
        assertThat(document.get("at")).isEqualTo(Instant.parse("2026-11-22T10:15:30.123Z"));
        assertThat(document.get("old")).isEqualTo(Instant.ofEpochMilli(-1000));
        assertThat(ArchiveDocument.uuidOf(document.get("u"))).isEqualTo(UUID.fromString("00010203-0405-0607-0809-0a0b0c0d0e0f"));
        assertThat(document.get("dec")).isEqualTo("1.25");
        assertThat(document).doesNotContainKey("_class");
        assertThat(((Map<?, ?>) document.get("nested")).keySet().stream().map(String::valueOf).toList()).containsExactly("keep");
    }

    @Test
    void loginArraysOfIpAddressesAreFoldedWhileDecoding() {
        Map<String, Object> document = decode("{\"ipAddresses\":[{\"logins\":[{\"$date\":\"2026-01-02T00:00:00Z\"},"
            + "{\"$date\":\"2026-01-01T00:00:00Z\"},{\"$date\":\"2026-01-03T00:00:00Z\"}]}]}");
        Map<?, ?> address = (Map<?, ?>) ((List<?>) document.get("ipAddresses")).getFirst();

        assertThat(address.get("logins")).isEqualTo(new FoldedDates(Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-03T00:00:00Z"), 3));
    }

    @Test
    void dollarKeysAndUnknownWrappersInFormAAreRejected() {
        assertThatThrownBy(() -> decode("{\"a\":1,\"$where\":2}")).isInstanceOf(UnreadableDocumentException.class);
        assertThatThrownBy(() -> decode("{\"a\":{\"$regex\":\"x\"}}")).isInstanceOf(UnreadableDocumentException.class);
        assertThatThrownBy(() -> decode("{\"$modlBson\":\"AAAA\",\"other\":1}")).isInstanceOf(UnreadableDocumentException.class);
        assertThatThrownBy(() -> decode("[1,2]")).isInstanceOf(UnreadableDocumentException.class);
    }

    @Test
    void loneSurrogatesAndNulAreCleaned() {
        Map<String, Object> document = decode("{\"text\":\"a\\u0000b\\ud800c\"}");
        assertThat(document.get("text")).isEqualTo("ab�c");
    }

    @Test
    void rawBsonDecodesNestedDocumentsAndRejectsForgedLengths() {
        byte[] valid = ArchiveAnalyzerTest.bsonDocumentWithString("$set", "value");
        assertThat(RawBsonDecoder.decode(valid)).containsEntry("$set", "value");

        byte[] forgedTotal = valid.clone();
        ByteBuffer.wrap(forgedTotal).order(ByteOrder.LITTLE_ENDIAN).putInt(0, valid.length + 10);
        assertThatThrownBy(() -> RawBsonDecoder.decode(forgedTotal)).isInstanceOf(UnreadableDocumentException.class);

        byte[] forgedString = valid.clone();
        ByteBuffer.wrap(forgedString).order(ByteOrder.LITTLE_ENDIAN).putInt(4 + 1 + 5, 1_000_000);
        assertThatThrownBy(() -> RawBsonDecoder.decode(forgedString)).isInstanceOf(UnreadableDocumentException.class);

        for (int cut = 1; cut < valid.length; cut++) {
            byte[] truncated = Arrays.copyOf(valid, cut);
            assertThatThrownBy(() -> RawBsonDecoder.decode(truncated)).isInstanceOf(UnreadableDocumentException.class);
        }
    }

    @Test
    void invalidUtf8InBsonIsRejected() {
        byte[] document = ArchiveAnalyzerTest.bsonDocumentWithString("k", "ab");
        document[document.length - 4] = (byte) 0xC0;
        assertThatThrownBy(() -> RawBsonDecoder.decode(document)).isInstanceOf(UnreadableDocumentException.class);
    }

    @Test
    void decimal128IsRenderedAsText() {
        assertThat(RawBsonDecoder.Decimal128Text.of(0x3040000000000000L, 125L)).isEqualTo("125");
        assertThat(RawBsonDecoder.Decimal128Text.of(0x303C000000000000L, 125L)).isEqualTo("1.25");
        assertThat(RawBsonDecoder.Decimal128Text.of(0x7C00000000000000L, 0)).isEqualTo("NaN");
    }

    private static Map<String, Object> decode(String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return ExtendedJsonDecoder.decodeLine(bytes, bytes.length);
    }
}
