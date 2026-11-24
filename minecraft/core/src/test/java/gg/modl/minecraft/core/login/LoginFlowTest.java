package gg.modl.minecraft.core.login;

import gg.modl.minecraft.api.http.request.PlayerLoginRequest;
import gg.modl.minecraft.api.http.response.PlayerLoginResponse;
import gg.modl.minecraft.core.HttpClientHolder;
import gg.modl.minecraft.core.TestHttpClients;
import gg.modl.minecraft.core.cache.LoginCache;
import gg.modl.minecraft.core.integration.mojang.WebPlayer;
import gg.modl.minecraft.core.support.FakeModlHttpClient;
import gg.modl.minecraft.core.support.MapLocaleManager;
import gg.modl.minecraft.core.support.RecordingPluginLogger;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginFlowTest {
    private static final long TIMEOUT_SECONDS = 5;
    private static final long SHORT_TIMEOUT_SECONDS = 1;
    private static final long LATE_RESPONSE_GRACE_MILLIS = 200;
    private static final String LOGIN_LANE_THREAD = "modl-http-login-test";
    private static final String BAN_CHECK_FAILED = "Unable to verify ban status.";
    private static final String INVALID_NAME = "Your player name is not valid on this server.";
    private static final String TEXTURE_OF_SKIN_ABC = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYWJjIn19fQ==";

    private final LoginService loginService = new LoginService(
            new MapLocaleManager().put("api_errors.ban_check_failed", BAN_CHECK_FAILED)
                    .put("api_errors.invalid_name", INVALID_NAME),
            null, null, null, null, null, null);

    @Test
    void collaboratorFailureDeniesInsteadOfCompletingExceptionally() throws Exception {
        LoginFlow flow = new LoginFlow(null, null, loginService, skinsThatNeverAnswer(),
                new ForwardedAddressNotice(new RecordingPluginLogger()), new InvalidNameNotice(new RecordingPluginLogger()),
                null, TIMEOUT_SECONDS);

        CompletableFuture<LoginService.LoginResult> verdict = flow.begin(attempt(UUID.randomUUID(), null));

        assertFalse(verdict.isCompletedExceptionally());
        assertInstanceOf(LoginService.LoginResult.Denied.class, verdict.get(1, TimeUnit.SECONDS));
    }

    @Test
    void theLoginIsSentAtOnceWithoutWaitingForASlowSkinLookup() throws Exception {
        AtomicReference<PlayerLoginRequest> sent = new AtomicReference<>();
        HttpClientHolder httpClientHolder = TestHttpClients.connected(new FakeModlHttpClient() {
            @Override
            public CompletableFuture<PlayerLoginResponse> playerLogin(PlayerLoginRequest request) {
                sent.set(request);
                return CompletableFuture.completedFuture(new PlayerLoginResponse());
            }
        });
        LoginCache loginCache = new LoginCache();
        LoginFlow flow = new LoginFlow(httpClientHolder, loginCache, loginService, skinsThatNeverAnswer(),
                new ForwardedAddressNotice(new RecordingPluginLogger()), new InvalidNameNotice(new RecordingPluginLogger()),
                "hub-1", TIMEOUT_SECONDS);

        try {
            flow.begin(attempt(UUID.randomUUID(), null)).get(1, TimeUnit.SECONDS);

            assertNotNull(sent.get());
            assertNull(sent.get().getSkinHash());
            assertEquals("203.0.113.7", sent.get().getIpAddress());
        } finally {
            loginCache.shutdown();
        }
    }

    @Test
    void theSkinFromTheLoginProfileIsSentWithTheLogin() throws Exception {
        AtomicReference<PlayerLoginRequest> sent = new AtomicReference<>();
        HttpClientHolder httpClientHolder = TestHttpClients.connected(new FakeModlHttpClient() {
            @Override
            public CompletableFuture<PlayerLoginResponse> playerLogin(PlayerLoginRequest request) {
                sent.set(request);
                return CompletableFuture.completedFuture(new PlayerLoginResponse());
            }
        });
        LoginCache loginCache = new LoginCache();
        LoginFlow flow = new LoginFlow(httpClientHolder, loginCache, loginService, skinsThatNeverAnswer(),
                new ForwardedAddressNotice(new RecordingPluginLogger()), new InvalidNameNotice(new RecordingPluginLogger()),
                "hub-1", TIMEOUT_SECONDS);

        try {
            flow.begin(attempt(UUID.randomUUID(), TEXTURE_OF_SKIN_ABC)).get(1, TimeUnit.SECONDS);

            assertEquals("abc", sent.get().getSkinHash());
        } finally {
            loginCache.shutdown();
        }
    }

    @Test
    void responseArrivingAfterTimeoutTriggersNoSideEffects() throws Exception {
        CompletableFuture<PlayerLoginResponse> pendingResponse = new CompletableFuture<>();
        HttpClientHolder httpClientHolder = TestHttpClients.connected(new FakeModlHttpClient() {
            @Override
            public CompletableFuture<PlayerLoginResponse> playerLogin(PlayerLoginRequest request) {
                return pendingResponse;
            }
        });
        LoginCache loginCache = new LoginCache();
        LoginFlow flow = new LoginFlow(httpClientHolder, loginCache, loginService, skinsThatNeverAnswer(),
                new ForwardedAddressNotice(new RecordingPluginLogger()), new InvalidNameNotice(new RecordingPluginLogger()),
                null, SHORT_TIMEOUT_SECONDS);
        UUID uuid = UUID.randomUUID();

        try {
            LoginService.LoginResult verdict = flow.begin(attempt(uuid, null)).get(SHORT_TIMEOUT_SECONDS * 4, TimeUnit.SECONDS);
            assertInstanceOf(LoginService.LoginResult.Denied.class, verdict);

            pendingResponse.complete(new PlayerLoginResponse());
            Thread.sleep(LATE_RESPONSE_GRACE_MILLIS);

            assertNull(loginCache.getCachedLoginResult(uuid));
        } finally {
            loginCache.shutdown();
        }
    }

    @Test
    void theVerdictIsSettledOnTheThreadThatCompletedTheLoginAndNeverOnTheCommonPool() throws Exception {
        CompletableFuture<PlayerLoginResponse> pendingResponse = new CompletableFuture<>();
        HttpClientHolder httpClientHolder = TestHttpClients.connected(new FakeModlHttpClient() {
            @Override
            public CompletableFuture<PlayerLoginResponse> playerLogin(PlayerLoginRequest request) {
                return pendingResponse;
            }
        });
        AtomicReference<String> settlingThread = new AtomicReference<>();
        LoginCache loginCache = new LoginCache() {
            @Override
            public void cacheLoginResult(UUID playerUuid, PlayerLoginResponse response, String skinHash) {
                settlingThread.set(Thread.currentThread().getName());
                super.cacheLoginResult(playerUuid, response, skinHash);
            }
        };
        LoginFlow flow = new LoginFlow(httpClientHolder, loginCache, loginService, skinsThatNeverAnswer(),
                new ForwardedAddressNotice(new RecordingPluginLogger()), new InvalidNameNotice(new RecordingPluginLogger()),
                "hub-1", TIMEOUT_SECONDS);

        try {
            CompletableFuture<LoginService.LoginResult> verdict = flow.begin(attempt(UUID.randomUUID(), null));
            Thread lane = new Thread(() -> pendingResponse.complete(new PlayerLoginResponse()), LOGIN_LANE_THREAD);
            lane.start();
            lane.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));

            assertNotNull(verdict.get(1, TimeUnit.SECONDS));
            assertEquals(LOGIN_LANE_THREAD, settlingThread.get());
        } finally {
            loginCache.shutdown();
        }
    }

    @Test
    void aNameOutsideTheMinecraftRuleIsRefusedWithItsOwnMessageAndNeverSent() throws Exception {
        AtomicReference<PlayerLoginRequest> sent = new AtomicReference<>();
        HttpClientHolder httpClientHolder = TestHttpClients.connected(new FakeModlHttpClient() {
            @Override
            public CompletableFuture<PlayerLoginResponse> playerLogin(PlayerLoginRequest request) {
                sent.set(request);
                return CompletableFuture.completedFuture(new PlayerLoginResponse());
            }
        });
        RecordingPluginLogger console = new RecordingPluginLogger();
        UUID uuid = UUID.randomUUID();
        LoginFlow flow = new LoginFlow(httpClientHolder, null, loginService, skinsThatNeverAnswer(),
                new ForwardedAddressNotice(new RecordingPluginLogger()), new InvalidNameNotice(console), "hub-1",
                TIMEOUT_SECONDS);

        LoginService.LoginResult verdict = flow.begin(new LoginAttempt(uuid, "bad name!", "203.0.113.7", "lobby", null))
                .get(1, TimeUnit.SECONDS);

        assertEquals(INVALID_NAME, loginService.denialMessage(verdict));
        assertNull(sent.get());
        assertTrue(console.warnings().stream().anyMatch(line -> line.contains(uuid.toString()) && !line.contains("bad name!")));
    }

    @Test
    void deniedResultAlwaysYieldsANonNullMessage() {
        String message = loginService.denialMessage(new LoginService.LoginResult.Denied(null));

        assertNotNull(message);
        assertEquals(BAN_CHECK_FAILED, message);
    }

    @Test
    void unknownResultDeniesByDefault() {
        assertEquals(BAN_CHECK_FAILED, loginService.denialMessage(null));
    }

    @Test
    void allowedResultYieldsNoDenialMessage() {
        assertNull(loginService.denialMessage(new LoginService.LoginResult.Allowed(null)));
    }

    private static LoginAttempt attempt(UUID uuid, String texture) {
        return new LoginAttempt(uuid, "Notch", "203.0.113.7", "lobby", texture);
    }

    private static LoginSkins skinsThatNeverAnswer() {
        return new LoginSkins(uuid -> new CompletableFuture<WebPlayer>());
    }
}
