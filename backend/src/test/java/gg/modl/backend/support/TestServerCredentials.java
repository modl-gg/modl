package gg.modl.backend.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public final class TestServerCredentials {
    private static final int ENV_FILE_SEARCH_DEPTH = 4;

    private static final Map<String, String> PROPS = loadProperties();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    private static volatile ProbeResult publicApiProbe;
    private static volatile ProbeResult panelApiProbe;

    private TestServerCredentials() {}

    public static boolean usesExternalServer() {
        String baseUrl = get("MODL_BASE_URL");
        return baseUrl != null && !baseUrl.isBlank();
    }

    public static String baseUrl() {
        return usesExternalServer() ? get("MODL_BASE_URL") : InProcessServer.get().baseUrl();
    }

    public static String apiKey() {
        return usesExternalServer() ? get("MODL_API_KEY") : InProcessServer.get().apiKey();
    }

    public static String sessionToken() {
        return usesExternalServer() ? get("MODL_SESSION_TOKEN") : InProcessServer.get().sessionToken();
    }

    public static String sessionKey() {
        return usesExternalServer() ? get("MODL_SESSION_KEY") : InProcessServer.get().sessionKey();
    }

    public static String databaseUrl() {return get("MODL_TEST_DATABASE_URL");}

    public static String databaseUsername() {return get("MODL_TEST_DATABASE_USERNAME");}

    public static String databasePassword() {return get("MODL_TEST_DATABASE_PASSWORD");}

    static String originOf(String url) {
        URI uri = URI.create(url);
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("Invalid base URL for origin derivation: " + url);
        }
        return uri.getPort() == -1
            ? uri.getScheme() + "://" + uri.getHost()
            : uri.getScheme() + "://" + uri.getHost() + ":" + uri.getPort();
    }

    public static String panelOrigin() {
        return originOf(baseUrl());
    }

    public static boolean isAvailable() {
        return baseUrl() != null && apiKey() != null && sessionToken() != null;
    }

    public static boolean isPublicApiAvailable() {
        return publicApiProbe().available();
    }

    public static String publicApiUnavailableReason() {
        return publicApiProbe().reason();
    }

    public static boolean isPanelApiAvailable() {
        return panelApiProbe().available();
    }

    public static String panelApiUnavailableReason() {
        return panelApiProbe().reason();
    }

    private static String get(String key) {
        String val = PROPS.get(key);
        if (val != null) {
            return val;
        }
        return System.getenv(key);
    }

    private static Map<String, String> loadProperties() {
        Map<String, String> map = new HashMap<>();
        Path envFile = locateEnvFile();
        if (envFile == null) {
            return map;
        }
        try {
            for (String line : Files.readAllLines(envFile)) {
                String trimmed = line.trim();
                int separator = trimmed.indexOf('=');
                if (!trimmed.isEmpty() && !trimmed.startsWith("#") && separator > 0) {
                    map.put(trimmed.substring(0, separator).trim(), trimmed.substring(separator + 1).trim());
                }
            }
        } catch (IOException unreadable) {
            return map;
        }
        return map;
    }

    private static Path locateEnvFile() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < ENV_FILE_SEARCH_DEPTH && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(".env.test");
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static ProbeResult publicApiProbe() {
        ProbeResult cached = publicApiProbe;
        if (cached != null) {
            return cached;
        }
        synchronized (TestServerCredentials.class) {
            if (publicApiProbe == null) {
                publicApiProbe = probe("/v1/public/settings", false);
            }
            return publicApiProbe;
        }
    }

    private static ProbeResult panelApiProbe() {
        ProbeResult cached = panelApiProbe;
        if (cached != null) {
            return cached;
        }
        synchronized (TestServerCredentials.class) {
            if (panelApiProbe == null) {
                panelApiProbe = probe("/v1/panel/auth/me", true);
            }
            return panelApiProbe;
        }
    }

    private static ProbeResult probe(String path, boolean authenticated) {
        if (!isAvailable()) {
            return new ProbeResult(false, "Test server credentials not configured");
        }

        try {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("User-Agent", "modl-backend-test-suite")
                .GET();
            if (authenticated) {
                request.header("Origin", panelOrigin())
                    .header("Referer", panelOrigin() + "/panel")
                    .header("Cookie", "MODL_SESSION=" + sessionToken())
                    .header("X-Modl-Session-Key", nullToEmpty(sessionKey()));
            }
            HttpResponse<String> response = HTTP_CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return new ProbeResult(true, "OK");
            }
            return new ProbeResult(false, path + " probe returned HTTP " + response.statusCode());
        } catch (Exception exception) {
            return new ProbeResult(false, path + " probe failed: " + exception.getClass().getSimpleName());
        }
    }

    private record ProbeResult(boolean available, String reason) {}

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
