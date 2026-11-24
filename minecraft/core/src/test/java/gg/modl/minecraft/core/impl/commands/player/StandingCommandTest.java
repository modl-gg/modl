package gg.modl.minecraft.core.impl.commands.player;

import dev.simplix.cirrus.player.CirrusPlayerWrapper;
import gg.modl.minecraft.api.AbstractPlayer;
import gg.modl.minecraft.api.SimplePunishment;
import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.api.http.response.PlayerStandingResponse;
import gg.modl.minecraft.core.HttpClientHolder;
import gg.modl.minecraft.core.TestHttpClients;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.cache.CachedProfile;
import gg.modl.minecraft.core.cache.CachedProfileRegistry;
import gg.modl.minecraft.core.config.ConfigManager;
import gg.modl.minecraft.core.impl.menus.StandingMenu;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.support.FakeCirrusPlayerWrapper;
import gg.modl.minecraft.core.support.FakeCommandActor;
import gg.modl.minecraft.core.support.FakeModlHttpClient;
import gg.modl.minecraft.core.support.FakePlatform;
import gg.modl.minecraft.core.support.MapLocaleManager;
import gg.modl.minecraft.core.support.RecordingPluginLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class StandingCommandTest {
    @TempDir
    Path tempDir;

    private final CachedProfileRegistry registry = new CachedProfileRegistry();

    @Test
    void standingReturnsWithoutWaitingForTheStandingRequest() {
        UUID playerUuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174100");
        FakePlatform platform = platform(playerUuid, true);
        TestStandingCommand command = command(platform, httpClient(new CompletableFuture<>()));
        TestActor actor = new TestActor(playerUuid);
        ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "standing-command-test");
            thread.setDaemon(true);
            return thread;
        });

        try {
            Future<?> invocation = executor.submit(() -> command.standing(actor));

            assertDoesNotThrow(() -> invocation.get(200, TimeUnit.MILLISECONDS));
            assertEquals(Collections.emptyList(), actor.messages());
            assertEquals(0, command.displayCount.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void standingUsesOnlyThePlayerSafeRequest() {
        UUID playerUuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174105");
        TestStandingCommand command = command(platform(playerUuid, true), httpClient(CompletableFuture.completedFuture(standing())));
        TestActor actor = new TestActor(playerUuid);

        command.standing(actor);

        assertEquals(1, command.displayCount.get());
        assertEquals(Collections.emptyList(), actor.messages());
    }

    @Test
    void standingDoesNotCreateACachedProfile() {
        UUID playerUuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174106");
        TestStandingCommand command = command(platform(playerUuid, true), httpClient(CompletableFuture.completedFuture(standing())));

        command.standing(new TestActor(playerUuid));

        assertNull(registry.getProfile(playerUuid));
    }

    @Test
    void standingOnlySetsItsCooldownOnTheSessionProfile() {
        UUID playerUuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174107");
        CachedProfile profile = registry.createProfile(playerUuid);
        SimplePunishment mute = new SimplePunishment("MUTE", "MUTE", null, "Spam", "MUTE0001", null, null, null, true, 1, false);
        profile.setActiveMute(mute);
        TestStandingCommand command = command(platform(playerUuid, true), httpClient(CompletableFuture.completedFuture(standing())));

        command.standing(new TestActor(playerUuid));

        assertSame(profile, registry.getProfile(playerUuid));
        assertSame(mute, profile.getActiveMute());
        assertTrue(profile.getCooldowns().isOnCooldown("standing", 60_000));
    }

    @Test
    void standingRepliesErrorWithoutDisplayWhenPlayerWrapperIsMissing() {
        UUID playerUuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174101");
        FakePlatform platform = new FakePlatform().register(new AbstractPlayer(playerUuid, "modlplayer", true));
        TestStandingCommand command = command(platform, httpClient(CompletableFuture.completedFuture(standing())));
        TestActor actor = new TestActor(playerUuid);

        command.standing(actor);

        assertEquals(1, platform.mainThreadScheduleCount());
        assertEquals(0, command.displayCount.get());
        assertEquals(Collections.singletonList(message("standing.error")), actor.messages());
    }

    @Test
    void standingSchedulesErrorReplyWhenTheResponseIsNull() {
        UUID playerUuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174102");
        FakePlatform platform = platform(playerUuid, false);
        CompletableFuture<PlayerStandingResponse> future = new CompletableFuture<>();
        TestStandingCommand command = command(platform, httpClient(future));
        TestActor actor = new TestActor(playerUuid);

        command.standing(actor);
        future.complete(null);

        assertErrorReplyIsScheduled(platform, command, actor);
    }

    @Test
    void standingSchedulesErrorReplyWhenTheResponseHasNoPunishmentList() {
        UUID playerUuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174103");
        FakePlatform platform = platform(playerUuid, false);
        TestStandingCommand command = command(platform, httpClient(CompletableFuture.completedFuture(new PlayerStandingResponse())));
        TestActor actor = new TestActor(playerUuid);

        command.standing(actor);

        assertErrorReplyIsScheduled(platform, command, actor);
    }

    @Test
    void standingSchedulesErrorReplyWhenTheRequestFails() {
        UUID playerUuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174104");
        FakePlatform platform = platform(playerUuid, false);
        CompletableFuture<PlayerStandingResponse> future = new CompletableFuture<>();
        TestStandingCommand command = command(platform, httpClient(future));
        TestActor actor = new TestActor(playerUuid);

        command.standing(actor);
        future.completeExceptionally(new IllegalStateException("boom"));

        assertErrorReplyIsScheduled(platform, command, actor);
    }

    private static void assertErrorReplyIsScheduled(FakePlatform platform, TestStandingCommand command, TestActor actor) {
        assertEquals(1, platform.mainThreadScheduleCount());
        assertEquals(Collections.emptyList(), actor.messages());

        platform.runScheduledTasks();

        assertEquals(Collections.singletonList(message("standing.error")), actor.messages());
        assertEquals(0, command.displayCount.get());
    }

    private static FakePlatform platform(UUID playerUuid, boolean autoRunMainThread) {
        return new FakePlatform()
                .register(new AbstractPlayer(playerUuid, "modlplayer", true))
                .registerWrapper(playerUuid, new FakeCirrusPlayerWrapper(playerUuid))
                .autoRunMainThread(autoRunMainThread);
    }

    private TestStandingCommand command(FakePlatform platform, ModlHttpClient httpClient) {
        LocaleManager localeManager = new MapLocaleManager().withFallback(StandingCommandTest::message);
        ConfigManager configManager = new ConfigManager(tempDir, new RecordingPluginLogger());
        Cache cache = new Cache(registry);
        return new TestStandingCommand(TestHttpClients.connected(httpClient), platform, localeManager, configManager, cache);
    }

    private static ModlHttpClient httpClient(CompletableFuture<PlayerStandingResponse> future) {
        return new FakeModlHttpClient() {
            @Override
            public CompletableFuture<PlayerStandingResponse> getPlayerStanding(UUID uuid) {
                return future;
            }
        };
    }

    private static PlayerStandingResponse standing() {
        return new PlayerStandingResponse("Low", "Low", 0, 0, Collections.emptyList());
    }

    private static String message(String path) {
        return "message:" + path;
    }

    private static class TestStandingCommand extends StandingCommand {
        private final AtomicInteger displayCount = new AtomicInteger();

        TestStandingCommand(HttpClientHolder httpClientHolder, Platform platform, LocaleManager localeManager,
                            ConfigManager configManager, Cache cache) {
            super(httpClientHolder, platform, localeManager, configManager, cache);
        }

        @Override
        protected StandingMenu createMenu(ModlHttpClient httpClient, UUID uuid, String username,
                                          PlayerStandingResponse standing) {
            return null;
        }

        @Override
        protected void displayMenu(StandingMenu menu, CirrusPlayerWrapper player) {
            displayCount.incrementAndGet();
        }
    }

    private static final class TestActor extends FakeCommandActor {
        TestActor(UUID uuid) {
            super(uuid, "modlplayer");
        }

        @Override
        public void sendRawError(String message) {
            fail(message);
        }
    }
}
