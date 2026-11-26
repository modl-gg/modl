package gg.modl.minecraft.core.impl.commands.staff;

import gg.modl.minecraft.api.AbstractPlayer;
import gg.modl.minecraft.core.PluginServices;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.cache.CachedProfileRegistry;
import gg.modl.minecraft.core.connection.PanelUrl;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.service.ActiveRecording;
import gg.modl.minecraft.core.service.RecordingOrigin;
import gg.modl.minecraft.core.service.ReplayCaptureResult;
import gg.modl.minecraft.core.service.ReplayMemory;
import gg.modl.minecraft.core.service.ReplayRecordingOutcome;
import gg.modl.minecraft.core.service.ReplayRecordingResult;
import gg.modl.minecraft.core.service.ReplayService;
import gg.modl.minecraft.core.support.FakeCommandActor;
import gg.modl.minecraft.core.support.FakePlatform;
import gg.modl.minecraft.core.support.TestPluginServices;
import gg.modl.minecraft.core.util.CommandUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayCommandTest {
    private static final UUID TARGET = UUID.fromString("123e4567-e89b-12d3-a456-426614174600");
    private static final long MEGABYTE = 1024L * 1024L;

    private final LocaleManager locale = new LocaleManager();
    private final FakePlatform platform = new FakePlatform().register(new AbstractPlayer(TARGET, "Steve", true));
    private final FakeCommandActor console = new FakeCommandActor(CommandUtil.CONSOLE_UUID, "CONSOLE");
    private final ReplayCommand command = new ReplayCommand(platform, locale, PanelUrl.of("https://panel.example.com"));
    private final FakeReplays replays = new FakeReplays();
    private PluginServices services;

    @BeforeEach
    void install() {
        services = TestPluginServices.install(new Cache(new CachedProfileRegistry()), locale);
        services.setReplayService(replays);
    }

    @AfterEach
    void uninstall() {
        PluginServices.uninstall(services);
    }

    @Test
    void recordStartsARecordingOfTheNamedPlayer() {
        replays.answer = ReplayRecordingResult.of(ReplayRecordingOutcome.OK);

        command.record(console, "Steve");

        assertEquals(Collections.singletonList("start " + TARGET + " Steve"), replays.calls);
        assertEquals(Collections.singletonList(message("replay.record_started")), console.messages());
    }

    @Test
    void recordingAPlayerWhoIsAlreadyRecordedSaysSo() {
        replays.answer = ReplayRecordingResult.of(ReplayRecordingOutcome.ALREADY_RECORDING);

        command.record(console, "Steve");

        assertEquals(Collections.singletonList(message("replay.already_recording")), console.messages());
    }

    @Test
    void stopEndsOnlyARecordingStaffStarted() {
        replays.answer = ReplayRecordingResult.of(ReplayRecordingOutcome.NOT_STARTED_BY_STAFF,
                new ActiveRecording(RecordingOrigin.ANTICHEAT_FLAG, 1_000L), null);
        command.stop(console, "Steve");
        replays.answer = ReplayRecordingResult.of(ReplayRecordingOutcome.OK);
        command.stop(console, "Steve");

        assertEquals(Arrays.asList("stop " + TARGET + " Steve", "stop " + TARGET + " Steve"), replays.calls);
        assertEquals(Arrays.asList(locale.getMessage("replay.recording_kept", mapOf("player", "Steve",
                "origin", locale.getMessage("replay.origins.anticheat_flag"))), message("replay.record_stopped")), console.messages());
    }

    @Test
    void statusShowsTheOriginTheDurationAndTheMemory() {
        replays.answer = ReplayRecordingResult.of(ReplayRecordingOutcome.OK,
                new ActiveRecording(RecordingOrigin.STAFF, 125_000L), new ReplayMemory(3 * MEGABYTE / 2, 64 * MEGABYTE, 2));

        command.status(console, "Steve");

        assertEquals(Arrays.asList(
                locale.getMessage("replay.status_recording", mapOf("player", "Steve",
                        "origin", locale.getMessage("replay.origins.staff"), "duration", "2m 5s")),
                locale.getMessage("replay.memory", mapOf("used", "1.5", "limit", "64.0", "recordings", "2"))),
                console.messages());
    }

    @Test
    void statusOfAnUnrecordedPlayerStillShowsTheMemory() {
        replays.answer = ReplayRecordingResult.of(ReplayRecordingOutcome.OK, null, new ReplayMemory(0, 64 * MEGABYTE, 0));

        command.status(console, "Steve");

        assertEquals(message("replay.status_not_recording"), console.messages().get(0));
        assertEquals(2, console.messages().size());
    }

    @Test
    void aPlayerOnAServerWithoutAHubLinkIsReportedUnreachable() {
        replays.answer = ReplayRecordingResult.of(ReplayRecordingOutcome.NO_SERVER_CONNECTED);

        command.record(console, "Steve");

        assertEquals(Collections.singletonList(message("replay.server_unreachable")), console.messages());
    }

    @Test
    void aFailedRequestAnswersThatTheRecordingCouldNotChange() {
        replays.failure = new IllegalStateException("boom");

        command.record(console, "Steve");

        assertEquals(Collections.singletonList(message("replay.record_failed")), console.messages());
    }

    @Test
    void aFailedStatusSaysTheStatusCouldNotBeRead() {
        replays.failure = new IllegalStateException("boom");

        command.status(console, "Steve");

        assertEquals(Collections.singletonList(message("replay.status_failed")), console.messages());
    }

    @Test
    void aPlayerWhoLeftBeforeTheRequestArrivedIsReportedGone() {
        replays.answer = ReplayRecordingResult.of(ReplayRecordingOutcome.NOT_LOCAL);

        command.record(console, "Steve");

        assertEquals(Collections.singletonList(message("replay.player_left")), console.messages());
    }

    @Test
    void anUnknownPlayerIsNotRequested() {
        command.record(console, "Nobody");

        assertTrue(replays.calls.isEmpty());
        assertEquals(Collections.singletonList(locale.getMessage("general.player_not_found")), console.messages());
    }

    @Test
    void theConsoleMustNameThePlayerForStatus() {
        command.status(console, null);

        assertTrue(replays.calls.isEmpty());
        assertEquals(Collections.singletonList(locale.getMessage("replay.console_specify_player")), console.messages());
    }

    @Test
    void withoutReplaysTheCommandSaysTheyAreNotAvailable() {
        services.setReplayService(null);

        command.record(console, "Steve");

        assertEquals(Collections.singletonList(locale.getMessage("replay.not_available")), console.messages());
    }

    private String message(String key) {
        return locale.getMessage(key, mapOf("player", "Steve"));
    }

    private static final class FakeReplays implements ReplayService {
        private final List<String> calls = new ArrayList<>();
        private ReplayRecordingResult answer = ReplayRecordingResult.of(ReplayRecordingOutcome.ERROR);
        private RuntimeException failure;

        @Override
        public CompletableFuture<ReplayCaptureResult> captureReplayResult(UUID targetUuid, String targetName) {
            calls.add("capture " + targetUuid + " " + targetName);
            return CompletableFuture.completedFuture(ReplayCaptureResult.noActiveRecording());
        }

        @Override
        public CompletableFuture<ReplayRecordingResult> startRecording(UUID targetUuid, String targetName) {
            return answer("start", targetUuid, targetName);
        }

        @Override
        public CompletableFuture<ReplayRecordingResult> stopRecording(UUID targetUuid, String targetName) {
            return answer("stop", targetUuid, targetName);
        }

        @Override
        public CompletableFuture<ReplayRecordingResult> recordingStatus(UUID targetUuid, String targetName) {
            return answer("status", targetUuid, targetName);
        }

        private CompletableFuture<ReplayRecordingResult> answer(String action, UUID targetUuid, String targetName) {
            calls.add(action + " " + targetUuid + " " + targetName);
            CompletableFuture<ReplayRecordingResult> result = new CompletableFuture<>();
            if (failure != null) result.completeExceptionally(failure);
            else result.complete(answer);
            return result;
        }
    }
}
