package gg.modl.backend.support;

import static gg.modl.backend.database.jooq.Tables.IMPORT_JOBS;

import com.google.gson.Gson;
import gg.modl.backend.BackendApplication;
import gg.modl.backend.backup.FileTrees;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.database.WriteAttemptHook;
import gg.modl.backend.database.sql.SqlDatabaseProperties;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.TestDialects;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

public final class InProcessServer {
    private static final String DIALECT_VARIABLE = "MODL_TEST_HTTP_DIALECT";
    private static final String API_KEY = "in-process-test-api-key-0123456789abcdefghijklmnop";
    private static final String OWNER_EMAIL = "owner@example.com";
    private static final String SETUP_CODE_FILE = "setup-code.txt";
    private static final Pattern SETUP_CODE = Pattern.compile("\\b[A-Z2-9]{4}-[A-Z2-9]{4}\\b");
    private static final String SESSION_COOKIE = "MODL_SESSION=";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final ConfigurableApplicationContext context;
    private final RetryInjection retryInjection;
    private final String baseUrl;
    private final String sessionToken;
    private final String sessionKey;

    private InProcessServer(ConfigurableApplicationContext context, RetryInjection retryInjection, String baseUrl,
                            OwnerSession session) {
        this.context = context;
        this.retryInjection = retryInjection;
        this.baseUrl = baseUrl;
        this.sessionToken = session.token();
        this.sessionKey = session.key();
    }

    public static InProcessServer get() {
        return Started.INSTANCE;
    }

    String baseUrl() {
        return baseUrl;
    }

    String apiKey() {
        return API_KEY;
    }

    String sessionToken() {
        return sessionToken;
    }

    String sessionKey() {
        return sessionKey;
    }

    DSLContext dsl() {
        return context.getBean(DSLContext.class);
    }

    public int injectedRetries() {
        return retryInjection.injected();
    }

    public void forgetImportJobs() {
        context.getBean(UnitOfWork.class).write(() -> {
            dsl().deleteFrom(IMPORT_JOBS).execute();
        });
    }

    private static InProcessServer start() {
        try {
            Path directory = Files.createTempDirectory("modl-in-process-server-");
            SpringApplication.getShutdownHandlers().add(() -> deleteDataDirectory(directory));
            RetryInjection retryInjection = new RetryInjection();
            SpringApplication application = BackendApplication.application(properties(directory));
            application.addInitializers(context ->
                context.getBeanFactory().registerSingleton(WriteAttemptHook.class.getName(), retryInjection));
            ConfigurableApplicationContext context = application.run();
            String baseUrl = "http://127.0.0.1:" + context.getEnvironment().getRequiredProperty("local.server.port");
            OwnerSession session = claim(baseUrl, directory.resolve(SETUP_CODE_FILE));
            InProcessServer server = new InProcessServer(context, retryInjection, baseUrl, session);
            new InProcessFixture(new ApiClient(baseUrl, API_KEY, session.token(), session.key())).seed();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("The in-process test server could not start", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while starting the in-process test server", e);
        }
    }

    private static void deleteDataDirectory(Path directory) {
        try {
            FileTrees.deleteRecursively(directory);
        } catch (IOException e) {
            throw new IllegalStateException("The in-process test server could not remove " + directory, e);
        }
    }

    private static Map<String, Object> properties(Path directory) {
        SqlDatabaseProperties database = TestDialects.freshDatabase(requestedDialect());
        Map<String, Object> properties = new HashMap<>();
        properties.put("modl.data-dir", directory.toString());
        properties.put("modl.database.url", database.externalUrl().orElse(""));
        properties.put("modl.database.username", Objects.requireNonNullElse(database.username(), ""));
        properties.put("modl.database.password", Objects.requireNonNullElse(database.password(), ""));
        properties.put("modl.instance.api-key", API_KEY);
        properties.put("modl.instance.internal-api-key", "");
        properties.put("modl.instance.admin-email", "");
        properties.put("modl.instance.public-url", "");
        properties.put("modl.instance.trust-proxy-headers", "true");
        properties.put("modl.setup.code-file", "");
        properties.put("modl.mail.host", "");
        properties.put("modl.ai.provider", "");
        properties.put("modl.ai.api-key", "");
        properties.put("modl.ai.gemini-api-key", "");
        properties.put("modl.storage.type", "local");
        properties.put("server.address", "127.0.0.1");
        properties.put("server.port", "0");
        return properties;
    }

    private static DialectTarget requestedDialect() {
        String name = System.getenv(DIALECT_VARIABLE);
        return name == null || name.isBlank() ? TestDialects.SQLITE : TestDialects.named(name);
    }

    private static OwnerSession claim(String baseUrl, Path codeFile) throws IOException, InterruptedException {
        Matcher printed = SETUP_CODE.matcher(Files.readString(codeFile));
        if (!printed.find()) {
            throw new IllegalStateException("The setup code file holds no code");
        }
        String code = printed.group();
        String body = new Gson().toJson(Map.of("code", code, "owner", Map.of("email", OWNER_EMAIL), "publicUrl", baseUrl));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/public/setup/claim"))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Origin", baseUrl)
            .POST(BodyPublishers.ofString(body))
            .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
            JsonHelper.assertStatus(response, 200);
            String cookie = response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith(SESSION_COOKIE))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("The setup claim issued no session cookie"));
            String token = cookie.substring(SESSION_COOKIE.length(), cookie.indexOf(';'));
            return new OwnerSession(token, JsonHelper.parseObject(response.body()).get("sessionKey").getAsString());
        }
    }

    private record OwnerSession(String token, String key) {
    }

    private static final class Started {
        private static final InProcessServer INSTANCE = start();
    }
}
