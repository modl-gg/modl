package gg.modl.backend.update.check;

import static gg.modl.backend.database.jooq.Tables.INSTANCE_STATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.support.MutableClock;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import gg.modl.backend.update.DeploymentDetector;
import gg.modl.backend.update.ReleaseBuild;
import gg.modl.backend.update.UpdateProperties;
import gg.modl.backend.update.state.InstanceState;
import gg.modl.backend.update.state.JooqInstanceStateRepository;
import gg.modl.update.ReleaseKeys;
import gg.modl.update.ReleaseVersion;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReleaseCheckTest {
    private static final String STABLE = "/releases/latest/download/modl-release.json";
    private static final ReleaseBuild BUILD = ReleaseBuild.of(ReleaseVersion.parse("3.0.0"), ReleaseVersion.parse("3.0.0"));

    @TempDir
    Path dataDirectory;

    private final Map<String, Served> served = new ConcurrentHashMap<>();
    private final List<Headers> requests = new CopyOnWriteArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final AtomicReference<Runnable> duringRequest = new AtomicReference<>(() -> { });
    private final UpdateNotices notices = mock(UpdateNotices.class);
    private HttpServer github;
    private SignedTestRelease signer;

    @BeforeEach
    void start() throws Exception {
        signer = new SignedTestRelease();
        github = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        github.createContext("/", this::answer);
        github.start();
    }

    @AfterEach
    void stop() {
        github.stop(0);
    }

    @SqlContractTest
    void theSecondCheckIsConditionalAndAnnouncesAVersionOnlyOnce(DialectTarget target) throws Exception {
        served.put(STABLE, new Served(signer.envelope("3.0.1"), "\"stable-1\""));
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            seedState(database, false);
            ReleaseChecker checker = checker(database, signer.keys(), null);

            checker.checkNow();
            checker.checkNow();

            assertThat(requests.get(0).getFirst("If-None-Match")).isNull();
            assertThat(requests.get(1).getFirst("If-None-Match")).isEqualTo("\"stable-1\"");
            InstanceState state = state(database);
            assertThat(state.lastCheckOutcome()).isEqualTo("SUCCEEDED");
            assertThat(state.stable().envelope()).contains("keyId");
            verify(notices, times(1)).releaseAvailable(any(), any(), any());
        }
    }

    @SqlContractTest
    void aFailureKeepsTheLastGoodReleaseAndBacksOff(DialectTarget target) throws Exception {
        served.put(STABLE, new Served(signer.envelope("3.0.1"), "\"stable-1\""));
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            seedState(database, false);
            ReleaseChecker checker = checker(database, signer.keys(), null);
            checker.checkNow();
            String good = state(database).stable().envelope();

            served.put(STABLE, new Served("not json".getBytes(StandardCharsets.UTF_8), "\"stable-2\""));
            checker.checkNow();

            InstanceState state = state(database);
            assertThat(state.lastCheckOutcome()).isEqualTo("INVALID_MANIFEST");
            assertThat(state.stable().envelope()).isEqualTo(good);
            assertThat(state.stable().etag()).isEqualTo("\"stable-1\"");
            assertThat(state.checkFailures()).isEqualTo(1);
        }
    }

    @SqlContractTest
    void theNextCheckIsStoredBeforeTheRequestLeaves(DialectTarget target) throws Exception {
        served.put(STABLE, new Served(signer.envelope("3.0.1"), "\"stable-1\""));
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            seedState(database, false);
            AtomicReference<Instant> whileRequesting = new AtomicReference<>();
            duringRequest.set(() -> whileRequesting.set(state(database).nextCheckAt()));

            checker(database, signer.keys(), null).checkNow();

            assertThat(whileRequesting.get()).isAfter(database.clock().instant());
        }
    }

    @SqlContractTest
    void preReleasesUseAConditionalListAndDownloadTheEnvelopeOnce(DialectTarget target) throws Exception {
        served.put(STABLE, new Served(signer.envelope("3.0.1"), "\"stable-1\""));
        served.put("/api/releases", new Served(("[{\"tag_name\":\"v3.2.0\",\"draft\":true},{\"tag_name\":\"v3.1.0-rc.1\",\"draft\":false},"
            + "{\"tag_name\":\"v3.0.1\",\"draft\":false}]").getBytes(StandardCharsets.UTF_8), "\"list-1\""));
        served.put("/releases/download/v3.1.0-rc.1/modl-release.json", new Served(signer.envelope("3.1.0-rc.1"), "\"rc-1\""));
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            seedState(database, true);
            ReleaseChecker checker = checker(database, signer.keys(), null);

            checker.checkNow();
            checker.checkNow();

            InstanceState state = state(database);
            assertThat(state.prerelease().etag()).isEqualTo("\"list-1\"");
            assertThat(paths).filteredOn(path -> path.contains("/download/v3.1.0-rc.1/")).hasSize(1);
            assertThat(new OfferedRelease(new ReleaseEnvelopes(signer.keys())).newerThan(BUILD.version(), state))
                .hasValueSatisfying(release -> assertThat(release.version().toString()).isEqualTo("3.1.0-rc.1"));
        }
    }

    @SqlContractTest
    void requestsCarryNothingButTheVersion(DialectTarget target) throws Exception {
        served.put(STABLE, new Served(signer.envelope("3.0.1"), "\"stable-1\""));
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            seedState(database, false);
            checker(database, signer.keys(), null).checkNow();

            Headers sent = requests.get(0);
            assertThat(sent.getFirst("User-Agent")).isEqualTo("modl/3.0.0");
            assertThat(sent.keySet()).extracting(String::toLowerCase)
                .containsOnly("user-agent", "accept", "host", "connection", "cache-control", "pragma")
                .doesNotContain("cookie", "authorization", "x-api-key");
        }
    }

    @SqlContractTest
    void optingOutOrHavingNoKeysMakesNoRequestAtAll(DialectTarget target) throws Exception {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            seedState(database, true);
            List<ReleaseChecker> off = new ArrayList<>();
            off.add(checker(database, signer.keys(), false));
            off.add(checker(database, ReleaseKeys.parse(""), null));

            for (ReleaseChecker checker : off) {
                checker.scheduleFirstCheck();
                checker.tick();
                assertThatThrownBy(checker::checkNow).isInstanceOf(ConflictException.class);
            }

            assertThat(paths).isEmpty();
            verifyNoInteractions(notices);
        }
    }

    @Test
    void redirectsStayOnTheAllowedHostsAndNeverLoseHttps() {
        ReleaseHttpClient client = new ReleaseHttpClient(UpdateProperties.defaults(), BUILD, new MutableClock(SqlTestDatabase.START));
        URI start = URI.create("https://github.com/modl-gg/modl/releases/latest/download/modl-release.json");

        assertThatThrownBy(() -> client.redirectTarget(start, "https://evil.example/modl-release.json")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> client.redirectTarget(start, "https://evilgithubusercontent.com/x")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> client.redirectTarget(start, "http://github.com/x")).isInstanceOf(IOException.class);
    }

    private ReleaseChecker checker(SqlTestDatabase database, ReleaseKeys keys, Boolean check) {
        String base = "http://127.0.0.1:" + github.getAddress().getPort();
        UpdateProperties properties = new UpdateProperties(check, null, null, base + "/releases", base + "/api/releases");
        ReleaseHttpClient http = new ReleaseHttpClient(properties, BUILD, database.clock());
        ReleaseEnvelopes envelopes = new ReleaseEnvelopes(keys);
        DeploymentDetector deployment = new DeploymentDetector(DeploymentDetector.DOCKER);
        return new ReleaseChecker(new JooqInstanceStateRepository(database.dsl()), database.unitOfWork(),
            new StableReleaseSource(http, envelopes, properties), new PrereleaseSource(http, envelopes, properties),
            new CheckPolicy(properties, envelopes), notices, deployment, BUILD, database.clock());
    }

    private static void seedState(SqlTestDatabase database, boolean prereleases) {
        database.unitOfWork().write(() -> {
            database.dsl().insertInto(INSTANCE_STATE)
                .set(INSTANCE_STATE.ID, 1)
                .set(INSTANCE_STATE.LAST_RUN_VERSION, "3.0.0")
                .set(INSTANCE_STATE.VERSION_CHANGED_AT, SqlTestDatabase.START)
                .set(INSTANCE_STATE.INCLUDE_PRERELEASES, prereleases)
                .execute();
        });
    }

    private static InstanceState state(SqlTestDatabase database) {
        return new JooqInstanceStateRepository(database.dsl()).find().orElseThrow();
    }

    private void answer(HttpExchange exchange) throws IOException {
        paths.add(exchange.getRequestURI().getPath());
        requests.add(exchange.getRequestHeaders());
        duringRequest.get().run();
        Served file = served.get(exchange.getRequestURI().getPath());
        if (file == null) {
            exchange.sendResponseHeaders(404, -1);
        } else if (file.etag().equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
            exchange.sendResponseHeaders(304, -1);
        } else {
            exchange.getResponseHeaders().add("ETag", file.etag());
            exchange.sendResponseHeaders(200, file.body().length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(file.body());
            }
        }
        exchange.close();
    }

    private static final class Served {
        private final byte[] body;
        private final String etag;

        Served(byte[] body, String etag) {
            this.body = body.clone();
            this.etag = etag;
        }

        byte[] body() {
            return body.clone();
        }

        String etag() {
            return etag;
        }
    }
}
