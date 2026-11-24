package gg.modl.backend.auth;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import gg.modl.backend.BackendApplication;
import gg.modl.backend.auth.totp.Base32;
import gg.modl.backend.auth.totp.Totp;
import gg.modl.backend.database.sql.CrashingEngineProcess;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class CredentialLogRedactionProcess {
    static final String VERDICT = "credential log verdict: ";
    static final String CLEAN = "clean";

    private static final String NOTHING_CAPTURED = "no Spring MVC debug event was captured";
    private static final String SPRING_WEB_LOGGERS = "org.springframework.web";
    private static final String OWNER_EMAIL = "owner@example.com";
    private static final String CLAIMED_PASSWORD = "marker-claimed-password-7f3a91";
    private static final String CHANGED_PASSWORD = "marker-changed-password-c24d08";
    private static final String WRONG_PASSWORD = "marker-mistyped-password-5be612";
    private static final String SESSION_COOKIE = "MODL_SESSION=";
    private static final Pattern SETUP_CODE = Pattern.compile("\\b[A-Z2-9]{4}-[A-Z2-9]{4}\\b");

    private CredentialLogRedactionProcess() {
    }

    public static void main(String[] args) throws Exception {
        CrashingEngineProcess.haltWhenParentIsGone();
        Path directory = Path.of(args[0]);
        try (ConfigurableApplicationContext context = BackendApplication.application(Map.of(
            "modl.data-dir", directory.toString(),
            "modl.database.url", "",
            "modl.instance.admin-email", "",
            "modl.instance.public-url", "",
            "modl.setup.code-file", "",
            "modl.mail.host", "",
            "server.address", "127.0.0.1",
            "server.port", "0",
            "logging.level.gg.modl.backend", "DEBUG",
            "logging.level.root", "DEBUG")).run()) {
            Logger root = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(Logger.ROOT_LOGGER_NAME);
            root.detachAndStopAllAppenders();
            ListAppender<ILoggingEvent> captured = new ListAppender<>();
            captured.start();
            root.addAppender(captured);

            Map<String, List<String>> markers = new CredentialFlow(
                "http://127.0.0.1:" + context.getEnvironment().getRequiredProperty("local.server.port")).run(directory);

            captured.stop();
            System.out.println(VERDICT + verdict(List.copyOf(captured.list), markers));
            System.out.flush();
        }
    }

    private static String verdict(List<ILoggingEvent> events, Map<String, List<String>> markers) {
        if (events.stream().noneMatch(event -> event.getLoggerName().startsWith(SPRING_WEB_LOGGERS))) {
            return NOTHING_CAPTURED;
        }
        String logged = events.stream().map(CredentialLogRedactionProcess::render).collect(Collectors.joining("\n"));
        String leaked = markers.entrySet().stream()
            .filter(marker -> marker.getValue().stream().anyMatch(logged::contains))
            .map(Map.Entry::getKey)
            .collect(Collectors.joining(", "));
        return leaked.isEmpty() ? CLEAN : "leaked " + leaked;
    }

    private static String render(ILoggingEvent event) {
        String message = event.getFormattedMessage();
        return event.getThrowableProxy() == null ? message : message + "\n" + ThrowableProxyUtil.asString(event.getThrowableProxy());
    }

    private static final class CredentialFlow {
        private final String baseUrl;
        private final JsonMapper json = JsonMapper.builder().build();
        private final HttpClient http = HttpClient.newHttpClient();
        private String cookie = "";
        private String sessionKey = "";

        private CredentialFlow(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        private Map<String, List<String>> run(Path directory) throws IOException, InterruptedException {
            signIn("/v1/public/setup/claim", Map.of("code", setupCode(directory), "publicUrl", baseUrl, "owner", Map.of(
                "email", OWNER_EMAIL)));
            expect(200, send("PUT", "/v1/panel/auth/password", Map.of("newPassword", CLAIMED_PASSWORD, "proof", Map.of())));
            switchToPasswordSignIn();
            expect(401, send("POST", "/v1/panel/auth/password/login", Map.of("email", OWNER_EMAIL, "password", WRONG_PASSWORD)));
            signIn("/v1/panel/auth/password/login", Map.of("email", OWNER_EMAIL, "password", CLAIMED_PASSWORD));
            expect(200, send("PUT", "/v1/panel/auth/password",
                Map.of("newPassword", CHANGED_PASSWORD, "proof", Map.of("currentPassword", CLAIMED_PASSWORD))));
            String secret = json.readTree(expect(200, send("POST", "/v1/panel/auth/totp", Map.of())).body()).get("secret").asString();
            String code = Totp.codeAt(Base32.decode(secret), Totp.stepAt(Instant.now()));
            JsonNode issued = json.readTree(expect(200, send("POST", "/v1/panel/auth/totp/confirm", Map.of("code", code))).body())
                .get("codes");

            Map<String, List<String>> markers = new LinkedHashMap<>();
            markers.put("a password", List.of(CLAIMED_PASSWORD, CHANGED_PASSWORD, WRONG_PASSWORD));
            markers.put("the authenticator secret", List.of(secret));
            markers.put("a recovery code", Stream.iterate(0, index -> index < issued.size(), index -> index + 1)
                .map(index -> issued.get(index).asString())
                .toList());
            return markers;
        }

        private void switchToPasswordSignIn() throws IOException, InterruptedException {
            String version = json.readTree(expect(200, get("/v1/panel/settings/sign-in")).body()).get("version").asString();
            expect(200, send("PUT", "/v1/panel/settings/sign-in",
                Map.of("expectedVersion", version, "method", "SIGN_IN_METHOD_PASSWORD", "secondFactorRequired", false)));
        }

        private void signIn(String path, Map<String, Object> body) throws IOException, InterruptedException {
            cookie = "";
            sessionKey = "";
            HttpResponse<String> response = expect(200, send("POST", path, body));
            String issued = response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith(SESSION_COOKIE))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(path + " issued no session cookie"));
            cookie = issued.substring(0, issued.indexOf(';'));
            sessionKey = json.readTree(response.body()).get("sessionKey").asString();
        }

        private HttpResponse<String> get(String path) throws IOException, InterruptedException {
            return exchange("GET", path, BodyPublishers.noBody());
        }

        private HttpResponse<String> send(String method, String path, Map<String, Object> body) throws IOException, InterruptedException {
            return exchange(method, path, BodyPublishers.ofString(json.writeValueAsString(body)));
        }

        private HttpResponse<String> exchange(String method, String path, BodyPublisher body)
            throws IOException, InterruptedException {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Origin", baseUrl)
                .method(method, body);
            if (!cookie.isEmpty()) {
                request.header("Cookie", cookie).header("X-Modl-Session-Key", sessionKey);
            }
            return http.send(request.build(), BodyHandlers.ofString());
        }

        private static HttpResponse<String> expect(int status, HttpResponse<String> response) {
            if (response.statusCode() != status) {
                throw new IllegalStateException(response.uri().getPath() + " answered " + response.statusCode() + " instead of " + status
                    + ": " + response.body());
            }
            return response;
        }

        private static String setupCode(Path directory) throws IOException {
            Matcher printed = SETUP_CODE.matcher(Files.readString(directory.resolve("setup-code.txt")));
            if (!printed.find()) {
                throw new IllegalStateException("The setup code file holds no code");
            }
            return printed.group();
        }
    }
}
