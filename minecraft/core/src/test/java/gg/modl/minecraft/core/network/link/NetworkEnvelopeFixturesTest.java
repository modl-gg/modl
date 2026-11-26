package gg.modl.minecraft.core.network.link;

import gg.modl.minecraft.core.network.Freeze;
import gg.modl.minecraft.core.network.MemberAction;
import gg.modl.minecraft.core.network.MemberActionKind;
import gg.modl.minecraft.core.network.MemberActionOutcome;
import gg.modl.minecraft.core.network.ModerationSnapshot;
import gg.modl.minecraft.core.network.ModerationState;
import gg.modl.minecraft.core.network.ProxyBackend;
import gg.modl.minecraft.core.network.StaffSessionState;
import gg.modl.minecraft.core.service.ActiveRecording;
import gg.modl.minecraft.core.service.RecordingOrigin;
import gg.modl.minecraft.core.service.ReplayCaptureStatus;
import gg.modl.minecraft.core.service.ReplayMemory;
import gg.modl.minecraft.core.service.ReplayRecordingOutcome;
import gg.modl.minecraft.core.service.ReplayRecordingResult;
import gg.modl.minecraft.core.support.FakePlatform;
import gg.modl.minecraft.core.support.RecordingPluginLogger;
import gg.modl.proto.fixtures.NetworkEnvelopeFixtures;
import gg.modl.proto.modl.v1.ModerationStateEvent;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import gg.modl.proto.modl.v1.ReplayCaptureRequest;
import gg.modl.proto.modl.v1.ReplayCaptureResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static gg.modl.proto.fixtures.NetworkEnvelopeFixtures.CORE_INSTANCE;
import static gg.modl.proto.fixtures.NetworkEnvelopeFixtures.MEMBER_NAME;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkEnvelopeFixturesTest {
    private final UUID staff = UUID.fromString(NetworkEnvelopeFixtures.STAFF);
    private final UUID target = UUID.fromString(NetworkEnvelopeFixtures.TARGET);
    private final List<RealtimeEnvelope> sent = new ArrayList<>();

    @Test
    void aCoreSendsAndAMemberReadsTheSharedModerationBytes() {
        ModerationSnapshot snapshot = new ModerationSnapshot("e1", 7, new ModerationState(
                Collections.singletonMap(target, new Freeze(staff, 42L)),
                Collections.singletonMap(staff, new StaffSessionState(true, true, target))));

        assertArrayEquals(NetworkEnvelopeFixtures.CORE_MODERATION_STATE.bytes(),
                RealtimeEnvelope.newBuilder().setModerationState(ModerationStateProtos.toProto(snapshot)).build().toByteArray());
        ModerationStateEvent received = NetworkEnvelopeFixtures.MEMBER_MODERATION_STATE.envelope().getModerationState();
        assertEquals(CORE_INSTANCE, received.getSourceInstanceId());
        assertEquals(snapshot, ModerationStateProtos.fromProto(received));
    }

    @Test
    void aCoreSendsTheSharedProxyServersBytes() {
        List<RealtimeEnvelope> reported = new ArrayList<>();
        ProxyServersReporter reporter = new ProxyServersReporter(() -> Collections.singletonList(
                new ProxyBackend(MEMBER_NAME, NetworkEnvelopeFixtures.MEMBER_ADDRESS)), reported::add, new RecordingPluginLogger());

        reporter.connected();

        assertEquals(1, reported.size());
        assertArrayEquals(NetworkEnvelopeFixtures.CORE_PROXY_SERVERS.bytes(), reported.get(0).toByteArray());
    }

    @Test
    void aMemberSendsAndACoreReadsTheSharedActionBytes() {
        MemberAction action = new MemberAction("action-1", MemberActionKind.TOGGLE_FREEZE_TARGET, staff, target, null, null, null, false);

        assertArrayEquals(NetworkEnvelopeFixtures.MEMBER_ACTION.bytes(),
                RealtimeEnvelope.newBuilder().setMemberAction(MemberActionProtos.toProto(action)).build().toByteArray());
        assertEquals(action.stampedBy(MEMBER_NAME, "lobby-instance", false),
                MemberActionProtos.fromProto(NetworkEnvelopeFixtures.CORE_MEMBER_ACTION.envelope().getMemberAction()));
    }

    @Test
    void aCoreSendsAndAMemberReadsTheSharedResultBytes() {
        assertArrayEquals(NetworkEnvelopeFixtures.CORE_ACTION_RESULT.bytes(), RealtimeEnvelope.newBuilder()
                .setMemberActionResult(MemberActionProtos.toResult("action-1", MemberActionOutcome.ACCEPTED, staff))
                .build().toByteArray());
        assertEquals(MemberActionOutcome.ACCEPTED, MemberActionProtos.outcomeOf(
                NetworkEnvelopeFixtures.MEMBER_ACTION_RESULT.envelope().getMemberActionResult().getStatus()));
    }

    @Test
    void aCoreSendsAndAMemberReadsTheSharedCaptureRequestBytes() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            assertFalse(new NetworkReplayService(new FakePlatform().onServer(target, MEMBER_NAME), sharedRoster(), sent::add,
                    scheduler).captureReplayResult(target, "Steve").isDone());
        } finally {
            scheduler.shutdownNow();
        }

        ReplayCaptureRequest request = sent.get(0).getReplayCaptureRequest().toBuilder().setRequestId("capture-1").build();
        assertArrayEquals(NetworkEnvelopeFixtures.CORE_CAPTURE_REQUEST.bytes(),
                RealtimeEnvelope.newBuilder().setReplayCaptureRequest(request).build().toByteArray());
        ReplayCaptureRequest received = NetworkEnvelopeFixtures.MEMBER_CAPTURE_REQUEST.envelope().getReplayCaptureRequest();
        assertEquals(CORE_INSTANCE, received.getRequesterInstanceId());
        assertEquals(target, Uuids.parse(received.getTargetUuid()));
    }

    @Test
    void aMemberSendsAndACoreReadsTheSharedCaptureResultBytes() {
        ReplayCaptureResult answer = ReplayCaptureResult.newBuilder().setRequestId("capture-1")
                .setOutcome(NetworkMemberLink.outcomeOf(ReplayCaptureStatus.OK)).setReplayId("replay-1").build();

        assertArrayEquals(NetworkEnvelopeFixtures.MEMBER_CAPTURE_RESULT.bytes(),
                RealtimeEnvelope.newBuilder().setReplayCaptureResult(answer).build().toByteArray());
        assertEquals("replay-1", NetworkReplayService.resultOf(
                NetworkEnvelopeFixtures.CORE_CAPTURE_RESULT.envelope().getReplayCaptureResult()).getReplayId());
    }

    @Test
    void aCoreSendsAndAMemberReadsTheSharedRecordRequestBytes() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            assertFalse(new NetworkReplayService(new FakePlatform().onServer(target, MEMBER_NAME), sharedRoster(), sent::add,
                    scheduler).startRecording(target, "Steve").isDone());
        } finally {
            scheduler.shutdownNow();
        }

        ReplayCaptureRequest request = sent.get(0).getReplayCaptureRequest().toBuilder().setRequestId("record-1").build();
        assertArrayEquals(NetworkEnvelopeFixtures.CORE_RECORD_REQUEST.bytes(),
                RealtimeEnvelope.newBuilder().setReplayCaptureRequest(request).build().toByteArray());
        assertEquals(CORE_INSTANCE, NetworkEnvelopeFixtures.MEMBER_RECORD_REQUEST.envelope().getReplayCaptureRequest()
                .getRequesterInstanceId());
    }

    @Test
    void aMemberSendsAndACoreReadsTheSharedRecordingResultBytes() {
        ReplayRecordingResult status = ReplayRecordingResult.of(ReplayRecordingOutcome.OK,
                new ActiveRecording(RecordingOrigin.STAFF, 65_000L), new ReplayMemory(3L * 1024 * 1024, 64L * 1024 * 1024, 1));

        assertArrayEquals(NetworkEnvelopeFixtures.MEMBER_RECORDING_RESULT.bytes(), RealtimeEnvelope.newBuilder()
                .setReplayCaptureResult(ReplayRecordingProtos.toResult("record-1", status)).build().toByteArray());
        assertEquals(status, ReplayRecordingProtos.fromResult(
                NetworkEnvelopeFixtures.CORE_RECORDING_RESULT.envelope().getReplayCaptureResult()));
    }

    @Test
    void aCoreReportsItsPlayersAsTheSharedBytes() {
        new HostedPlayersReporter(() -> Collections.singletonList(staff), sent::add, new RecordingPluginLogger()).connected();

        assertEquals(1, sent.size());
        assertArrayEquals(NetworkEnvelopeFixtures.CORE_HOSTED_PLAYERS.bytes(), sent.get(0).toByteArray());
    }

    @Test
    void aMemberReportsOnlyItsCountAsTheSharedBytes() {
        new HostedPlayerCountReporter(() -> 1, sent::add).connected();

        assertEquals(1, sent.size());
        assertArrayEquals(NetworkEnvelopeFixtures.MEMBER_HOSTED_PLAYERS.bytes(), sent.get(0).toByteArray());
    }

    @Test
    void aCoreReadsTheSharedRosterBytes() {
        assertTrue(sharedRoster().isEnforcedOn(MEMBER_NAME));
    }

    @Test
    void aCoreSendsAndAMemberExecutesTheSharedStatWipeBytes() {
        new NetworkStatWipeExecutor(sharedRoster(), sent::add)
                .executeStatWipe("Steve", target.toString(), "wipe-1", (outcome, server) -> { });
        assertArrayEquals(NetworkEnvelopeFixtures.CORE_STAT_WIPES.bytes(), sent.get(0).toByteArray());

        List<String> wiped = new ArrayList<>();
        NetworkMemberLink link = NetworkMemberLink.builder()
                .statWipes((username, uuid, punishmentId) -> wiped.add(username + ":" + punishmentId))
                .logger(new RecordingPluginLogger())
                .build();
        link.onStatWipes(NetworkEnvelopeFixtures.MEMBER_STAT_WIPES.envelope().getStatWipePush());
        assertEquals(Collections.singletonList("Steve:wipe-1"), wiped);
    }

    private static NetworkRoster sharedRoster() {
        NetworkRoster roster = new NetworkRoster();
        roster.replace(NetworkEnvelopeFixtures.CORE_ROSTER.envelope().getNetworkRoster());
        return roster;
    }
}
