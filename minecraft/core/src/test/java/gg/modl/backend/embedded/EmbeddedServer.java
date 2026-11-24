package gg.modl.backend.embedded;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EmbeddedServer implements Function<Map<String, Object>, AutoCloseable> {
    public static final String CLOSED_PROPERTY = "modl.test.fakeEmbeddedServer.closed";
    public static final String INTERNAL_KEY_PROPERTY = "modl.test.fakeEmbeddedServer.internalKey";
    public static final String STARTING_PROPERTY = "modl.test.fakeEmbeddedServer.starting";
    public static final String HOLD_PROPERTY = "modl.test.fakeEmbeddedServer.hold";
    public static final String FAIL_PROPERTY = "modl.test.fakeEmbeddedServer.fail";
    public static final String FAIL_UPDATE_PROPERTY = "modl.test.fakeEmbeddedServer.failUpdate";
    private static final long HOLD_LIMIT_MILLIS = 30_000;
    private static final long HOLD_POLL_MILLIS = 10;

    @Override
    @SuppressWarnings("unchecked")
    public AutoCloseable apply(Map<String, Object> input) {
        Map<String, String> properties = (Map<String, String>) input.get("properties");
        System.setProperty(STARTING_PROPERTY, "true");
        holdWhileRequested();
        if (Boolean.getBoolean(FAIL_PROPERTY)) throw new IllegalStateException("fake server start failure");
        settleUpdate(Paths.get(properties.get("MODL_DATA_DIR"), "updates"));
        System.setProperty(INTERNAL_KEY_PROPERTY, properties.get("MODL_INTERNAL_API_KEY"));
        String publicUrl = properties.containsKey("MODL_PUBLIC_URL")
                ? properties.get("MODL_PUBLIC_URL")
                : "http://localhost:" + properties.get("MODL_PORT");
        return new Handle(publicUrl);
    }

    private static void settleUpdate(Path updates) {
        Path pendingFile = updates.resolve("pending.json");
        if (!Files.exists(pendingFile)) return;
        try {
            String pending = new String(Files.readAllBytes(pendingFile), StandardCharsets.UTF_8);
            if (!"ROLLBACK".equals(field(pending, "phase"))) {
                if (Boolean.getBoolean(FAIL_UPDATE_PROPERTY)) throw new IllegalStateException("fake update start failure");
                String committed = "{\"format\":1,\"transactionId\":\"" + field(pending, "id")
                        + "\",\"kind\":\"EMBEDDED\",\"version\":\"" + field(pending, "to")
                        + "\",\"artifact\":\"" + field(pending, "toArtifact")
                        + "\",\"committedAt\":\"2026-12-01T12:00:00Z\"}";
                Files.write(updates.resolve("committed.json"), committed.getBytes(StandardCharsets.UTF_8));
            }
            Files.delete(pendingFile);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String field(String json, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static void holdWhileRequested() {
        long deadline = System.currentTimeMillis() + HOLD_LIMIT_MILLIS;
        while (Boolean.getBoolean(HOLD_PROPERTY) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(HOLD_POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static final class Handle implements AutoCloseable, Supplier<Map<String, String>> {
        private final String publicUrl;

        private Handle(String publicUrl) {
            this.publicUrl = publicUrl;
        }

        @Override
        public Map<String, String> get() {
            Map<String, String> details = new HashMap<>();
            details.put("publicUrl", publicUrl);
            return details;
        }

        @Override
        public void close() {
            System.setProperty(CLOSED_PROPERTY, "true");
        }
    }
}
