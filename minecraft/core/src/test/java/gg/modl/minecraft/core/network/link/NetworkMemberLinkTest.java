package gg.modl.minecraft.core.network.link;

import gg.modl.minecraft.core.network.MemberAction;
import gg.modl.minecraft.core.network.MemberActionKind;
import gg.modl.minecraft.core.network.MemberActionOutcome;
import gg.modl.minecraft.core.network.ModerationSnapshot;
import gg.modl.minecraft.core.network.ModerationSnapshotListener;
import gg.modl.minecraft.core.service.ActiveRecording;
import gg.modl.minecraft.core.service.RecordingOrigin;
import gg.modl.minecraft.core.service.ReplayCaptureStatus;
import gg.modl.minecraft.core.service.ReplayMemory;
import gg.modl.minecraft.core.service.ReplayRecordingOutcome;
import gg.modl.minecraft.core.service.ReplayRecordingResult;
import gg.modl.minecraft.core.support.RecordingPluginLogger;
import gg.modl.proto.modl.v1.ModerationStateEvent;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import gg.modl.proto.modl.v1.ReplayCaptureOutcome;
import gg.modl.proto.modl.v1.ReplayCaptureRequest;
import gg.modl.proto.modl.v1.ReplayCaptureResult;
import gg.modl.proto.modl.v1.ReplayRecordingOrigin;
import gg.modl.proto.modl.v1.ReplayRequestAction;
import gg.modl.proto.modl.v1.StatWipePushEvent;
import gg.modl.proto.modl.v1.SyncPendingStatWipe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkMemberLinkTest {
    private final List<String> events = new ArrayList<>();
    private final List<String> wipes = new ArrayList<>();
    private final RecordingPluginLogger logger = new RecordingPluginLogger();
    private final FakeReplayRequests replays = new FakeReplayRequests(ReplayRecordingResult.of(ReplayRecordingOutcome.OK,
            new ActiveRecording(RecordingOrigin.STAFF, 5_000L), new ReplayMemory(1024L, 2048L, 1)));
    private final NetworkMemberLink link = NetworkMemberLink.builder()
            .moderation(new RecordingModeration())
            .captures(replays)
            .statWipes((username, uuid, punishmentId) -> wipes.add(username + ":" + punishmentId))
            .onlinePlayerCount(() -> 0)
            .serverName(() -> "lobby")
            .credentialsRejected(() -> events.add("restart"))
            .configPath("plugins/modl/connection.yml")
            .logger(logger)
            .build();

    @AfterEach
    void stop() {
        link.shutdown();
    }

    @Test
    void aReplacedSessionNamesTheServerAndTheFix() {
        assertEquals("[network] Another server connected to the modl network as lobby with this server key, so this server was "
                + "disconnected; reconnecting in 60 seconds. Give every server its own key "
                + "(Panel > Settings > General > Minecraft servers > Add server).", NetworkMemberLink.replacedNotice("lobby"));
    }

    @Test
    void withoutASessionActionsAnswerNoCoreAtOnce() {
        AtomicReference<MemberActionOutcome> outcome = new AtomicReference<>();

        link.send(MemberAction.playerAction(MemberActionKind.TOGGLE_VANISH, UUID.randomUUID(), null), outcome::set);

        assertEquals(MemberActionOutcome.NO_CORE, outcome.get());
        assertFalse(link.isConnected());
    }

    @Test
    void aRecordingRequestRunsItsActionAndAnswersWithTheRecordingState() {
        List<RealtimeEnvelope> answers = new ArrayList<>();

        link.onReplayRequest(replayRequest(ReplayRequestAction.REPLAY_REQUEST_ACTION_START_RECORDING), answers::add);
        link.onReplayRequest(replayRequest(ReplayRequestAction.REPLAY_REQUEST_ACTION_STOP_RECORDING), answers::add);
        link.onReplayRequest(replayRequest(ReplayRequestAction.REPLAY_REQUEST_ACTION_RECORDING_STATUS), answers::add);

        assertEquals(Arrays.asList("start Steve", "stop Steve", "status Steve"), replays.calls());
        ReplayCaptureResult answer = answers.get(0).getReplayCaptureResult();
        assertEquals("r1", answer.getRequestId());
        assertEquals(ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_OK, answer.getOutcome());
        assertEquals(ReplayRecordingOrigin.REPLAY_RECORDING_ORIGIN_STAFF, answer.getRecording().getOrigin());
        assertEquals(5_000L, answer.getRecording().getRecordingMs());
        assertEquals(2048L, answer.getMemory().getLimitBytes());
    }

    @Test
    void aReplayRequestWithoutAKnownActionDoesNothingAndAnswersAnError() {
        List<RealtimeEnvelope> answers = new ArrayList<>();

        link.onReplayRequest(replayRequest(ReplayRequestAction.REPLAY_REQUEST_ACTION_UNSPECIFIED), answers::add);
        link.onReplayRequest(replayRequest(ReplayRequestAction.REPLAY_REQUEST_ACTION_START_RECORDING).toBuilder()
                .setTargetUuid("not-a-uuid").build(), answers::add);

        assertTrue(replays.calls().isEmpty());
        assertEquals(2, answers.size());
        for (RealtimeEnvelope answer : answers) {
            assertEquals(ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_ERROR, answer.getReplayCaptureResult().getOutcome());
        }
    }

    @Test
    void snapshotsAndRetirementsReachTheReconcilerPerSource() {
        link.onModeration(ModerationStateEvent.newBuilder().setSourceInstanceId("proxy-1").setEpoch("e").setRevision(3).build());
        link.onModeration(ModerationStateEvent.newBuilder().setSourceInstanceId("proxy-1").setRetired(true).build());

        assertEquals(Arrays.asList("accept hub:proxy-1 3", "retire hub:proxy-1"), events);
    }

    @Test
    void statWipesWithAnInvalidPlayerIdAreSkipped() {
        link.onStatWipes(StatWipePushEvent.newBuilder()
                .addStatWipes(SyncPendingStatWipe.newBuilder().setMinecraftUuid("bad").setUsername("Steve").setPunishmentId("p1"))
                .addStatWipes(SyncPendingStatWipe.newBuilder().setMinecraftUuid(UUID.randomUUID().toString()).setUsername("Alex")
                        .setPunishmentId("p2"))
                .build());

        assertEquals(Collections.singletonList("Alex:p2"), wipes);
        assertEquals(1, logger.warnings().size());
    }

    @Test
    void captureStatusesMapOntoHubOutcomes() {
        assertEquals(ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_NOT_HOSTED, NetworkMemberLink.outcomeOf(ReplayCaptureStatus.NOT_LOCAL));
        assertEquals(ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_OK, NetworkMemberLink.outcomeOf(ReplayCaptureStatus.OK));
        assertEquals(ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_ERROR, NetworkMemberLink.outcomeOf(null));
    }

    @Test
    void theConsoleLinesNameTheServerAndTheRemedy() {
        assertEquals("[network] Connected to the modl network at https://panel.example as lobby.",
                NetworkMemberLink.connectedNotice("https://panel.example", "lobby"));
        assertEquals("[network] Connected to the modl network at https://panel.example.",
                NetworkMemberLink.connectedNotice("https://panel.example", ""));
        assertTrue(NetworkMemberLink.rejectedKeyNotice("plugins/modl/connection.yml").endsWith("plugins/modl/connection.yml."));
    }

    private final class RecordingModeration implements ModerationSnapshotListener {
        @Override
        public void accept(String sourceId, ModerationSnapshot snapshot) {
            events.add("accept " + sourceId + " " + snapshot.getRevision());
        }

        @Override
        public void retire(String sourceId) {
            events.add("retire " + sourceId);
        }

        @Override
        public void linkUp() {
            events.add("up");
        }

        @Override
        public void linkDown() {
            events.add("down");
        }
    }

    private static ReplayCaptureRequest replayRequest(ReplayRequestAction action) {
        return ReplayCaptureRequest.newBuilder().setRequestId("r1").setTargetUuid(UUID.randomUUID().toString())
                .setTargetName("Steve").setExpectedServer("lobby").setAction(action).build();
    }
}
