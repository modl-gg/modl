package gg.modl.minecraft.core.impl.http;

import gg.modl.minecraft.api.http.ApiClientException;
import gg.modl.minecraft.api.http.PanelUnavailableException;
import gg.modl.minecraft.core.support.TestConnections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailedResponseLoggingTest {
    private static final byte[] INVALID_CODE = "{\"message\":\"invalid_code\",\"code\":\"INVALID_ARGUMENT\"}"
            .getBytes(StandardCharsets.UTF_8);

    private final ModlHttpClientV2Impl client = new ModlHttpClientV2Impl(TestConnections.to("http://localhost", "api-key"), false);
    private final Logger logger = Logger.getLogger(ModlHttpClientV2Impl.class.getName());
    private final Recorder recorder = new Recorder();

    FailedResponseLoggingTest() {
        logger.addHandler(recorder);
    }

    @AfterEach
    void detach() {
        logger.removeHandler(recorder);
        client.shutdown();
    }

    @Test
    void aRefusalTheCallerHandlesLeavesTheConsoleQuiet() {
        RuntimeException error = client.toError("V2-1", request(), 400, INVALID_CODE);

        assertTrue(error instanceof ApiClientException);
        assertEquals(Collections.<String>emptyList(), recorder.messages);
    }

    @Test
    void anUnavailablePanelLeavesTheConsoleQuiet() {
        RuntimeException error = client.toError("V2-2", request(), 503, INVALID_CODE);

        assertTrue(error instanceof PanelUnavailableException);
        assertEquals(Collections.<String>emptyList(), recorder.messages);
    }

    @Test
    void aRejectedApiKeyIsReported() {
        client.toError("V2-3", request(), 401, INVALID_CODE);

        assertEquals(1, recorder.messages.size());
        assertTrue(recorder.messages.get(0).contains("check the API key"));
    }

    @Test
    void aWrongEndpointIsReported() {
        client.toError("V2-4", request(), 405, INVALID_CODE);

        assertEquals(1, recorder.messages.size());
    }

    @Test
    void debugModeShowsEveryRefusal() {
        ModlHttpClientV2Impl debugClient = new ModlHttpClientV2Impl(TestConnections.to("http://localhost", "api-key"), true);
        try {
            debugClient.toError("V2-5", request(), 400, INVALID_CODE);
        } finally {
            debugClient.shutdown();
        }

        assertEquals(1, recorder.messages.size());
        assertTrue(recorder.messages.get(0).contains("HTTP 400"));
    }

    private AbstractModlHttpTransport.HttpRequest request() {
        return client.newRequest("/v1/minecraft/panel/link", "POST", null, null, Collections.<String, String>emptyMap());
    }

    private static final class Recorder extends Handler {
        private final List<String> messages = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.INFO.intValue()) messages.add(record.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
