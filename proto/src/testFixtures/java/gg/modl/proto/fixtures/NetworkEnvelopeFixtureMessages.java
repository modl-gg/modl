package gg.modl.proto.fixtures;

import gg.modl.proto.modl.v1.FrozenPlayer;
import gg.modl.proto.modl.v1.HostedPlayersEvent;
import gg.modl.proto.modl.v1.MemberActionKind;
import gg.modl.proto.modl.v1.MemberActionRequest;
import gg.modl.proto.modl.v1.MemberActionResult;
import gg.modl.proto.modl.v1.MemberActionStatus;
import gg.modl.proto.modl.v1.ModerationStateEvent;
import gg.modl.proto.modl.v1.NetworkMember;
import gg.modl.proto.modl.v1.NetworkRosterEvent;
import gg.modl.proto.modl.v1.ProxiedServer;
import gg.modl.proto.modl.v1.ProxyServersEvent;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import gg.modl.proto.modl.v1.ReplayCaptureOutcome;
import gg.modl.proto.modl.v1.ReplayCaptureRequest;
import gg.modl.proto.modl.v1.ReplayCaptureResult;
import gg.modl.proto.modl.v1.ReplayMemoryUse;
import gg.modl.proto.modl.v1.ReplayRecordingOrigin;
import gg.modl.proto.modl.v1.ReplayRecordingState;
import gg.modl.proto.modl.v1.ReplayRequestAction;
import gg.modl.proto.modl.v1.ServerRole;
import gg.modl.proto.modl.v1.StaffSession;
import gg.modl.proto.modl.v1.StatWipePushEvent;
import gg.modl.proto.modl.v1.SyncPendingStatWipe;

import static gg.modl.proto.fixtures.NetworkEnvelopeFixtures.CORE_INSTANCE;
import static gg.modl.proto.fixtures.NetworkEnvelopeFixtures.MEMBER_ADDRESS;
import static gg.modl.proto.fixtures.NetworkEnvelopeFixtures.MEMBER_NAME;
import static gg.modl.proto.fixtures.NetworkEnvelopeFixtures.STAFF;
import static gg.modl.proto.fixtures.NetworkEnvelopeFixtures.TARGET;

public final class NetworkEnvelopeFixtureMessages {
    static final String MEMBER_INSTANCE = "lobby-instance";
    static final String PLUGIN_VERSION = "3.0.0";
    static final String ACTION_ID = "action-1";
    static final String CAPTURE_ID = "capture-1";
    static final String REPLAY_ID = "replay-1";
    static final String RECORD_ID = "record-1";
    static final long RECORDING_MS = 65_000L;
    static final long MEMORY_USED_BYTES = 3L * 1024 * 1024;
    static final long MEMORY_LIMIT_BYTES = 64L * 1024 * 1024;
    static final String TARGET_NAME = "Steve";
    static final String WIPE_ID = "wipe-1";

    private NetworkEnvelopeFixtureMessages() {
    }

    public static RealtimeEnvelope of(NetworkEnvelopeFixtures fixture) {
        return switch (fixture) {
            case CORE_MODERATION_STATE -> envelope().setModerationState(moderationState()).build();
            case MEMBER_MODERATION_STATE -> envelope().setModerationState(moderationState().setSourceInstanceId(CORE_INSTANCE)).build();
            case MEMBER_ACTION -> envelope().setMemberAction(memberAction()).build();
            case CORE_MEMBER_ACTION -> envelope().setMemberAction(memberAction().setMemberName(MEMBER_NAME).setMemberInstanceId(MEMBER_INSTANCE)).build();
            case CORE_ACTION_RESULT, MEMBER_ACTION_RESULT -> envelope().setMemberActionResult(MemberActionResult.newBuilder()
                    .setRequestId(ACTION_ID)
                    .setStatus(MemberActionStatus.MEMBER_ACTION_STATUS_ACCEPTED)
                    .setActorUuid(STAFF)).build();
            case CORE_CAPTURE_REQUEST -> envelope().setReplayCaptureRequest(captureRequest()).build();
            case MEMBER_CAPTURE_REQUEST -> envelope().setReplayCaptureRequest(captureRequest().setRequesterInstanceId(CORE_INSTANCE)).build();
            case CORE_RECORD_REQUEST -> envelope().setReplayCaptureRequest(recordRequest()).build();
            case MEMBER_RECORD_REQUEST -> envelope().setReplayCaptureRequest(recordRequest().setRequesterInstanceId(CORE_INSTANCE)).build();
            case MEMBER_RECORDING_RESULT, CORE_RECORDING_RESULT -> envelope().setReplayCaptureResult(ReplayCaptureResult.newBuilder()
                    .setRequestId(RECORD_ID)
                    .setOutcome(ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_OK)
                    .setRecording(ReplayRecordingState.newBuilder()
                            .setOrigin(ReplayRecordingOrigin.REPLAY_RECORDING_ORIGIN_STAFF)
                            .setRecordingMs(RECORDING_MS))
                    .setMemory(ReplayMemoryUse.newBuilder()
                            .setUsedBytes(MEMORY_USED_BYTES)
                            .setLimitBytes(MEMORY_LIMIT_BYTES)
                            .setRecordings(1))).build();
            case MEMBER_CAPTURE_RESULT, CORE_CAPTURE_RESULT -> envelope().setReplayCaptureResult(ReplayCaptureResult.newBuilder()
                    .setRequestId(CAPTURE_ID)
                    .setOutcome(ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_OK)
                    .setReplayId(REPLAY_ID)).build();
            case CORE_HOSTED_PLAYERS -> envelope().setHostedPlayers(HostedPlayersEvent.newBuilder()
                    .setReplace(true)
                    .addJoinedUuids(STAFF)
                    .setSequence(1)
                    .setOnlineCount(1)).build();
            case MEMBER_HOSTED_PLAYERS -> envelope().setHostedPlayers(HostedPlayersEvent.newBuilder()
                    .setSequence(1)
                    .setOnlineCount(1)).build();
            case CORE_ROSTER -> envelope().setNetworkRoster(NetworkRosterEvent.newBuilder()
                    .addMembers(NetworkMember.newBuilder()
                            .setInstanceId(MEMBER_INSTANCE)
                            .setName(MEMBER_NAME)
                            .setRole(ServerRole.SERVER_ROLE_MEMBER)
                            .setPluginVersion(PLUGIN_VERSION)
                            .setOnlinePlayers(1))).build();
            case CORE_STAT_WIPES, MEMBER_STAT_WIPES -> envelope().setStatWipePush(StatWipePushEvent.newBuilder()
                    .addStatWipes(SyncPendingStatWipe.newBuilder()
                            .setMinecraftUuid(TARGET)
                            .setUsername(TARGET_NAME)
                            .setPunishmentId(WIPE_ID))).build();
            case CORE_PROXY_SERVERS -> envelope().setProxyServers(ProxyServersEvent.newBuilder()
                    .addServers(ProxiedServer.newBuilder().setName(MEMBER_NAME).setAddress(MEMBER_ADDRESS))).build();
        };
    }

    private static RealtimeEnvelope.Builder envelope() {
        return RealtimeEnvelope.newBuilder();
    }

    private static ModerationStateEvent.Builder moderationState() {
        return ModerationStateEvent.newBuilder()
                .setEpoch("e1")
                .setRevision(7)
                .addFrozen(FrozenPlayer.newBuilder().setMinecraftUuid(TARGET).setStaffUuid(STAFF).setFrozenAt(42))
                .addStaff(StaffSession.newBuilder().setMinecraftUuid(STAFF).setStaffMode(true).setVanished(true).setTargetUuid(TARGET));
    }

    private static MemberActionRequest.Builder memberAction() {
        return MemberActionRequest.newBuilder()
                .setRequestId(ACTION_ID)
                .setKind(MemberActionKind.MEMBER_ACTION_KIND_TOGGLE_FREEZE_TARGET)
                .setActorUuid(STAFF)
                .setTargetUuid(TARGET);
    }

    private static ReplayCaptureRequest.Builder captureRequest() {
        return replayRequest(CAPTURE_ID, ReplayRequestAction.REPLAY_REQUEST_ACTION_CAPTURE);
    }

    private static ReplayCaptureRequest.Builder recordRequest() {
        return replayRequest(RECORD_ID, ReplayRequestAction.REPLAY_REQUEST_ACTION_START_RECORDING);
    }

    private static ReplayCaptureRequest.Builder replayRequest(String requestId, ReplayRequestAction action) {
        return ReplayCaptureRequest.newBuilder()
                .setRequestId(requestId)
                .setTargetUuid(TARGET)
                .setTargetName(TARGET_NAME)
                .setExpectedServer(MEMBER_NAME)
                .setAction(action);
    }
}
