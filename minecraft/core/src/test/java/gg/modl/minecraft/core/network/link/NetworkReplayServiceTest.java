package gg.modl.minecraft.core.network.link;

import gg.modl.minecraft.core.service.ReplayCaptureResult;
import gg.modl.minecraft.core.service.ReplayCaptureStatus;
import gg.modl.minecraft.core.support.FakePlatform;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import gg.modl.proto.modl.v1.ReplayCaptureOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkReplayServiceTest {
    private final UUID target = UUID.randomUUID();
    private final FakePlatform platform = new FakePlatform().onServer(target, "lobby");
    private final NetworkRoster roster = new NetworkRoster();
    private final List<RealtimeEnvelope> sent = new ArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final NetworkReplayService replays = new NetworkReplayService(platform, roster, envelope -> sent.add(envelope),
            scheduler);

    @AfterEach
    void stop() {
        scheduler.shutdownNow();
    }

    @Test
    void aCaptureIsAddressedToThePlayersServerAndCompletesFromTheResult() {
        roster.replace(NetworkStatWipeExecutorTest.roster("lobby"));

        CompletableFuture<ReplayCaptureResult> capture = replays.captureReplayResult(target, "Steve");

        assertEquals("lobby", sent.get(0).getReplayCaptureRequest().getExpectedServer());
        replays.onResult(gg.modl.proto.modl.v1.ReplayCaptureResult.newBuilder()
                .setRequestId(sent.get(0).getReplayCaptureRequest().getRequestId())
                .setOutcome(ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_OK).setReplayId("replay-1").build());

        assertEquals("replay-1", capture.join().getReplayId());
    }

    @Test
    void aFinishedCaptureCancelsItsTimeout() {
        ScheduledThreadPoolExecutor timeouts = new ScheduledThreadPoolExecutor(1);
        timeouts.setRemoveOnCancelPolicy(true);
        try {
            NetworkReplayService service = new NetworkReplayService(platform, roster, envelope -> sent.add(envelope), timeouts);
            roster.replace(NetworkStatWipeExecutorTest.roster("lobby"));
            assertFalse(service.captureReplayResult(target, "Steve").isDone());
            assertEquals(1, timeouts.getQueue().size());

            service.onResult(gg.modl.proto.modl.v1.ReplayCaptureResult.newBuilder()
                    .setRequestId(sent.get(0).getReplayCaptureRequest().getRequestId())
                    .setOutcome(ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_OK).build());

            assertTrue(timeouts.getQueue().isEmpty());
        } finally {
            timeouts.shutdownNow();
        }
    }

    @Test
    void aSecondCaptureOfTheSamePlayerSharesThePendingOne() {
        roster.replace(NetworkStatWipeExecutorTest.roster("lobby"));

        CompletableFuture<ReplayCaptureResult> first = replays.captureReplayResult(target, "Steve");

        assertSame(first, replays.captureReplayResult(target, "Steve"));
        assertEquals(1, sent.size());
    }

    @Test
    void withoutAMemberOnThatServerNothingIsSent() {
        roster.replace(NetworkStatWipeExecutorTest.roster("survival"));

        assertEquals(ReplayCaptureStatus.NO_SERVER_CONNECTED, replays.captureReplayResult(target, "Steve").join().getStatus());
        assertFalse(replays.shouldAttemptCapture(target));
        assertTrue(sent.isEmpty());
    }

    @Test
    void aDisconnectEndsPendingCaptures() {
        roster.replace(NetworkStatWipeExecutorTest.roster("lobby"));
        CompletableFuture<ReplayCaptureResult> capture = replays.captureReplayResult(target, "Steve");

        replays.disconnected();

        assertEquals(ReplayCaptureStatus.NO_SERVER_CONNECTED, capture.join().getStatus());
    }

    @Test
    void hubOutcomesMapOntoTheCaptureStatuses() {
        assertEquals(ReplayCaptureStatus.NOT_LOCAL, NetworkReplayService.resultOf(result(ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_NOT_HOSTED)).getStatus());
        assertEquals(ReplayCaptureStatus.NO_SERVER_CONNECTED,
                NetworkReplayService.resultOf(result(ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_MEMBER_DISCONNECTED)).getStatus());
        assertEquals(ReplayCaptureStatus.ERROR, NetworkReplayService.resultOf(result(ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_TIMED_OUT)).getStatus());
        assertEquals(ReplayCaptureStatus.ERROR, NetworkReplayService.resultOf(result(ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_OK)).getStatus());
    }

    private static gg.modl.proto.modl.v1.ReplayCaptureResult result(ReplayCaptureOutcome outcome) {
        return gg.modl.proto.modl.v1.ReplayCaptureResult.newBuilder().setRequestId("r").setOutcome(outcome).build();
    }
}
