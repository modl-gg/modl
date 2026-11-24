package gg.modl.backend.migration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RecordBoundedInputStreamTest {
    private static final long MAX_BYTES = 64;
    private static final long MAX_VALUES = 10;

    @Test
    void bytesOutsideARecordAreOnlyCountedForThePosition() throws IOException {
        RecordBoundedInputStream stream = bounded("x".repeat(500));

        assertThat(stream.readAllBytes()).hasSize(500);
        assertThat(stream.position()).isEqualTo(500);
        assertThat(stream.limitExceeded()).isFalse();
    }

    @Test
    void aRecordWithTooManyBytesIsRefused() {
        RecordBoundedInputStream stream = bounded("x".repeat(65));
        stream.startRecord();

        assertThatThrownBy(stream::readAllBytes).isInstanceOf(IOException.class);
        assertThat(stream.limitExceeded()).isTrue();
    }

    @Test
    void aRecordWithTooManyValuesIsRefused() {
        RecordBoundedInputStream stream = bounded("[1,1,1,1,1,1,1,1,1,1,1]");
        stream.startRecord();

        assertThatThrownBy(stream::readAllBytes).isInstanceOf(IOException.class);
        assertThat(stream.limitExceeded()).isTrue();
    }

    @Test
    void everyRecordGetsItsOwnAllowance() throws IOException {
        RecordBoundedInputStream stream = bounded("x".repeat(120));

        stream.startRecord();
        assertThat(stream.readNBytes(60)).hasSize(60);
        stream.endRecord();
        stream.startRecord();
        assertThat(stream.readNBytes(60)).hasSize(60);

        assertThat(stream.limitExceeded()).isFalse();
    }

    @Test
    void aParserStopsAtTheLimit() {
        RecordBoundedInputStream stream = bounded("{\"text\":\"" + "x".repeat(500) + "\"}");
        stream.startRecord();

        assertThatThrownBy(() -> JsonMapper.builder().build().readTree(stream)).isInstanceOf(RuntimeException.class);
        assertThat(stream.limitExceeded()).isTrue();
    }

    private static RecordBoundedInputStream bounded(String content) {
        return new RecordBoundedInputStream(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
            MAX_BYTES, MAX_VALUES);
    }
}
