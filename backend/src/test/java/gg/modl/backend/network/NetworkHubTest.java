package gg.modl.backend.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.instance.MinecraftCredential;
import gg.modl.backend.realtime.auth.RealtimePrincipal;
import gg.modl.backend.realtime.lifecycle.RealtimeConnectionClosedEvent;
import gg.modl.backend.realtime.state.RealtimeConnectionState;
import gg.modl.backend.realtime.state.RealtimeOutbox;
import gg.modl.backend.serverkey.ServerKey;
import gg.modl.backend.serverkey.ServerKeyScope;
import gg.modl.backend.serverkey.service.ServerKeyCache;
import gg.modl.backend.support.MutableClock;
import gg.modl.backend.support.TestServers;
import gg.modl.proto.fixtures.NetworkEnvelopeFixtures;
import gg.modl.proto.modl.v1.FrozenPlayer;
import gg.modl.proto.modl.v1.HostedPlayersEvent;
import gg.modl.proto.modl.v1.MemberActionKind;
import gg.modl.proto.modl.v1.MemberActionRequest;
import gg.modl.proto.modl.v1.MemberActionResult;
import gg.modl.proto.modl.v1.MemberActionStatus;
import gg.modl.proto.modl.v1.ModerationStateEvent;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import gg.modl.proto.modl.v1.RealtimeEnvelope.PayloadCase;
import gg.modl.proto.modl.v1.ReplayCaptureOutcome;
import gg.modl.proto.modl.v1.ReplayCaptureRequest;
import gg.modl.proto.modl.v1.RealtimeCloseCode;
import gg.modl.proto.modl.v1.ReplayCaptureResult;
import gg.modl.proto.modl.v1.ServerRole;
import gg.modl.proto.modl.v1.StatWipePushEvent;
import gg.modl.proto.modl.v1.SyncPendingStatWipe;
import gg.modl.proto.modl.v1.Topic;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

class NetworkHubTest {
    private static final String STAFF = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final String TARGET = "1f8fad5b-d9cb-469f-a165-70867728950e";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-22T12:00:00Z"));
    private final RecordingOutbound outbound = new RecordingOutbound();
    private final ServerKeyCache serverKeys = mock(ServerKeyCache.class);
    private final NetworkSessionRegistry registry = new NetworkSessionRegistry();
    private NetworkHub hub;

    @BeforeEach
    void setUp() {
        hub = new NetworkHub(registry, outbound, new ModerationStateCache(clock), new HostedPlayerIndex(),
            new PendingMemberActions(clock), new PendingReplayCaptures(clock), serverKeys, clock);
        when(serverKeys.findById("lobby-key")).thenReturn(Optional.of(key(false)));
    }

    @Test
    void aMemberActionIsStampedRoutedToTheCoreAndAnsweredBack() {
        RealtimeConnectionState core = core("proxy-instance");
        RealtimeConnectionState member = member("lobby-key", "lobby");

        assertThat(hub.accept(member, envelope(action(MemberActionKind.MEMBER_ACTION_KIND_TOGGLE_VANISH, "r1").toBuilder()
            .setMemberName("spoofed").setProxyCommandGranted(true).build()))).isEqualTo(NetworkHub.Disposition.HANDLED);

        MemberActionRequest forwarded = only(core, PayloadCase.MEMBER_ACTION).getMemberAction();
        assertThat(forwarded.getMemberName()).isEqualTo("lobby");
        assertThat(forwarded.getProxyCommandGranted()).isFalse();
        assertThat(forwarded.getMemberInstanceId()).isEqualTo("lobby-instance");

        hub.accept(core, RealtimeEnvelope.newBuilder().setMemberActionResult(MemberActionResult.newBuilder()
            .setRequestId("r1").setStatus(MemberActionStatus.MEMBER_ACTION_STATUS_ACCEPTED)).build());
        MemberActionResult answer = only(member, PayloadCase.MEMBER_ACTION_RESULT).getMemberActionResult();
        assertThat(answer.getStatus()).isEqualTo(MemberActionStatus.MEMBER_ACTION_STATUS_ACCEPTED);
        assertThat(answer.getActorUuid()).isEqualTo(STAFF);
    }

    @Test
    void aProxyCommandNeedsTheGrantAndAToggleTakesEffectAtOnce() {
        RealtimeConnectionState core = core("proxy-instance");
        RealtimeConnectionState member = member("lobby-key", "lobby");

        hub.accept(member, proxyCommand("p1"));
        assertThat(only(member, PayloadCase.MEMBER_ACTION_RESULT).getMemberActionResult().getStatus())
            .isEqualTo(MemberActionStatus.MEMBER_ACTION_STATUS_NOT_PERMITTED);
        assertThat(outbound.to(core.getConnectionId())).extracting(RealtimeEnvelope::getPayloadCase)
            .doesNotContain(PayloadCase.MEMBER_ACTION);

        hub.refreshGrants(key(true));
        clock.advance(Duration.ofSeconds(2));
        hub.accept(member, proxyCommand("p2"));

        assertThat(only(core, PayloadCase.MEMBER_ACTION).getMemberAction().getProxyCommandGranted()).isTrue();
    }

    @Test
    void withoutACoreTheMemberHearsNoCoreAndPendingActionsEndWhenTheCoreLeaves() {
        RealtimeConnectionState member = member("lobby-key", "lobby");
        hub.accept(member, envelope(action(MemberActionKind.MEMBER_ACTION_KIND_TOGGLE_VANISH, "r1")));
        assertThat(only(member, PayloadCase.MEMBER_ACTION_RESULT).getMemberActionResult().getStatus())
            .isEqualTo(MemberActionStatus.MEMBER_ACTION_STATUS_NO_CORE);

        RealtimeConnectionState core = core("proxy-instance");
        outbound.sent.clear();
        hub.accept(member, envelope(action(MemberActionKind.MEMBER_ACTION_KIND_TOGGLE_VANISH, "r2")));
        hub.onClosed(new RealtimeConnectionClosedEvent(core));

        assertThat(only(member, PayloadCase.MEMBER_ACTION_RESULT).getMemberActionResult())
            .satisfies(result -> {
                assertThat(result.getRequestId()).isEqualTo("r2");
                assertThat(result.getStatus()).isEqualTo(MemberActionStatus.MEMBER_ACTION_STATUS_NO_CORE);
            });
    }

    @Test
    void anActionGoesToTheCoreThatHostsTheActor() {
        RealtimeConnectionState first = core("proxy-a");
        RealtimeConnectionState second = core("proxy-b");
        RealtimeConnectionState member = member("lobby-key", "lobby");
        hub.accept(second, RealtimeEnvelope.newBuilder().setHostedPlayers(HostedPlayersEvent.newBuilder()
            .setReplace(true).addJoinedUuids(STAFF).setOnlineCount(1)).build());

        hub.accept(member, envelope(action(MemberActionKind.MEMBER_ACTION_KIND_TOGGLE_VANISH, "r1")));

        assertThat(outbound.to(first.getConnectionId())).extracting(RealtimeEnvelope::getPayloadCase)
            .doesNotContain(PayloadCase.MEMBER_ACTION);
        assertThat(only(second, PayloadCase.MEMBER_ACTION).getMemberAction().getRequestId()).isEqualTo("r1");
    }

    @Test
    void onlyCoresPublishModerationAndMembersGetTheLatestCachedState() {
        RealtimeConnectionState core = core("proxy-instance");
        RealtimeConnectionState early = member("lobby-key", "lobby");

        assertThat(hub.accept(early, moderation("e1", 1))).isEqualTo(NetworkHub.Disposition.FORBIDDEN);
        hub.accept(core, moderation("e1", 7).toBuilder()
            .setModerationState(moderation("e1", 7).getModerationState().toBuilder().setSourceInstanceId("spoofed")).build());

        RecordingOutbound.Sent delivered = outbound.sent.stream()
            .filter(sent -> sent.connectionId().equals(early.getConnectionId())).findFirst().orElseThrow();
        assertThat(delivered.slot()).isEqualTo("moderation:proxy-instance");
        assertThat(delivered.envelope().getModerationState().getSourceInstanceId()).isEqualTo("proxy-instance");

        when(serverKeys.findById("survival-key")).thenReturn(Optional.of(new ServerKey("survival-key", "survival",
            ServerKeyScope.BEHIND_PROXY, false, "s".repeat(64), "abcdefgh", null, Instant.EPOCH, null)));
        RealtimeConnectionState late = member("survival-key", "survival");
        assertThat(only(late, PayloadCase.MODERATION_STATE).getModerationState().getRevision()).isEqualTo(7);
    }

    @Test
    void aDepartedCoreIsRetiredAfterTwoMinutesUnlessItReturns() {
        RealtimeConnectionState core = core("proxy-instance");
        RealtimeConnectionState member = member("lobby-key", "lobby");
        hub.accept(core, moderation("e1", 1));
        hub.onClosed(new RealtimeConnectionClosedEvent(core));
        outbound.sent.clear();

        clock.advance(Duration.ofSeconds(119));
        hub.sweep();
        assertThat(outbound.to(member.getConnectionId())).isEmpty();
        clock.advance(Duration.ofSeconds(2));
        hub.sweep();

        assertThat(only(member, PayloadCase.MODERATION_STATE).getModerationState().getRetired()).isTrue();
    }

    @Test
    void capturesGoOnlyToTheNamedMemberAndEndWhenItLeavesOrTimesOut() {
        RealtimeConnectionState core = core("proxy-instance");
        RealtimeConnectionState member = member("lobby-key", "lobby");

        hub.accept(core, capture("c1", "survival"));
        assertThat(only(core, PayloadCase.REPLAY_CAPTURE_RESULT).getReplayCaptureResult().getOutcome())
            .isEqualTo(ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_NO_MEMBER);

        outbound.sent.clear();
        hub.accept(core, capture("c2", "LOBBY"));
        assertThat(only(member, PayloadCase.REPLAY_CAPTURE_REQUEST).getReplayCaptureRequest().getRequesterInstanceId())
            .isEqualTo("proxy-instance");
        hub.accept(member, RealtimeEnvelope.newBuilder().setReplayCaptureResult(ReplayCaptureResult.newBuilder()
            .setRequestId("c2").setOutcome(ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_OK).setReplayId("replay-1")).build());
        assertThat(only(core, PayloadCase.REPLAY_CAPTURE_RESULT).getReplayCaptureResult().getReplayId()).isEqualTo("replay-1");

        outbound.sent.clear();
        clock.advance(Duration.ofSeconds(1));
        hub.accept(core, capture("c3", "lobby"));
        clock.advance(Duration.ofMinutes(10).plusSeconds(1));
        hub.sweep();
        assertThat(only(core, PayloadCase.REPLAY_CAPTURE_RESULT).getReplayCaptureResult().getOutcome())
            .isEqualTo(ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_TIMED_OUT);

        outbound.sent.clear();
        clock.advance(Duration.ofSeconds(1));
        hub.accept(core, capture("c4", "lobby"));
        hub.onClosed(new RealtimeConnectionClosedEvent(member));
        assertThat(only(core, PayloadCase.REPLAY_CAPTURE_RESULT).getReplayCaptureResult().getOutcome())
            .isEqualTo(ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_MEMBER_DISCONNECTED);
    }

    @Test
    void statWipesFromACoreReachEveryMemberAndNeverAnotherCore() {
        RealtimeConnectionState core = core("proxy-a");
        RealtimeConnectionState otherCore = core("proxy-b");
        RealtimeConnectionState member = member("lobby-key", "lobby");
        outbound.sent.clear();

        hub.accept(core, RealtimeEnvelope.newBuilder().setStatWipePush(StatWipePushEvent.newBuilder()
            .addStatWipes(SyncPendingStatWipe.newBuilder().setMinecraftUuid(TARGET).setUsername("Steve").setPunishmentId("p1")))
            .build());

        assertThat(only(member, PayloadCase.STAT_WIPE_PUSH).getStatWipePush().getStatWipesCount()).isEqualTo(1);
        assertThat(outbound.to(otherCore.getConnectionId())).isEmpty();
        assertThat(hub.accept(member, RealtimeEnvelope.newBuilder().setStatWipePush(StatWipePushEvent.getDefaultInstance()).build()))
            .isEqualTo(NetworkHub.Disposition.FORBIDDEN);
    }

    @Test
    void revokingAKeyClosesItsSessionsAndAKeyRevokedDuringTheHandshakeIsRefused() {
        RealtimeConnectionState member = member("lobby-key", "lobby");

        hub.revoke(key(false));
        assertThat(outbound.closed).containsExactly(member.getConnectionId());

        when(serverKeys.findById("gone-key")).thenReturn(Optional.empty());
        assertThat(hub.admit(socket(), unauthenticated(), principal(ServerRole.SERVER_ROLE_MEMBER,
            new MinecraftCredential(ServerKeyScope.BEHIND_PROXY, "gone-key", "gone"), "gone-instance", "gone")))
            .isEqualTo(NetworkHub.Admission.KEY_REVOKED);
        assertThat(registry.sessions()).extracting(LiveNetworkSession::instanceId).doesNotContain("gone-instance");
    }

    @Test
    void theRosterListsMembersForCores() {
        RealtimeConnectionState core = core("proxy-instance");
        member("lobby-key", "lobby");
        outbound.sent.clear();

        hub.publishRosterIfChanged();

        assertThat(only(core, PayloadCase.NETWORK_ROSTER).getNetworkRoster().getMembersList())
            .singleElement().satisfies(entry -> assertThat(entry.getName()).isEqualTo("lobby"));
        assertThat(registry.connectedKeyIds()).containsExactly("lobby-key");
    }

    @Test
    void theHubTurnsTheSharedEnvelopesIntoExactlyTheBytesThePluginsTestAgainst() {
        RealtimeConnectionState core = core(NetworkEnvelopeFixtures.CORE_INSTANCE);
        RealtimeConnectionState member = member("lobby-key", NetworkEnvelopeFixtures.MEMBER_NAME);
        outbound.sent.clear();

        accept(member, NetworkEnvelopeFixtures.MEMBER_HOSTED_PLAYERS);
        accept(core, NetworkEnvelopeFixtures.CORE_HOSTED_PLAYERS);
        accept(core, NetworkEnvelopeFixtures.CORE_MODERATION_STATE);
        accept(member, NetworkEnvelopeFixtures.MEMBER_ACTION);
        accept(core, NetworkEnvelopeFixtures.CORE_ACTION_RESULT);
        accept(core, NetworkEnvelopeFixtures.CORE_CAPTURE_REQUEST);
        accept(member, NetworkEnvelopeFixtures.MEMBER_CAPTURE_RESULT);
        accept(core, NetworkEnvelopeFixtures.CORE_STAT_WIPES);
        hub.publishRosterIfChanged();

        assertThat(outbound.to(core.getConnectionId())).extracting(RealtimeEnvelope::toByteArray).containsExactly(
            NetworkEnvelopeFixtures.CORE_MEMBER_ACTION.bytes(),
            NetworkEnvelopeFixtures.CORE_CAPTURE_RESULT.bytes(),
            NetworkEnvelopeFixtures.CORE_ROSTER.bytes());
        assertThat(outbound.to(member.getConnectionId())).extracting(RealtimeEnvelope::toByteArray).containsExactly(
            NetworkEnvelopeFixtures.MEMBER_MODERATION_STATE.bytes(),
            NetworkEnvelopeFixtures.MEMBER_ACTION_RESULT.bytes(),
            NetworkEnvelopeFixtures.MEMBER_CAPTURE_REQUEST.bytes(),
            NetworkEnvelopeFixtures.MEMBER_STAT_WIPES.bytes());
    }

    @Test
    void aSecondServerWithTheSameNameReplacesTheFirstAndCapturesReachTheNewest() {
        RealtimeConnectionState core = core("proxy-instance");
        RealtimeConnectionState first = member("lobby-key", "lobby");
        RealtimeConnectionState second = member(new MinecraftCredential(ServerKeyScope.BEHIND_PROXY, "lobby-key", "lobby"),
            "copied-instance", "lobby");
        RealtimeConnectionState fullCredential = member(MinecraftCredential.instanceKey(), "full-instance", "LOBBY");

        assertThat(outbound.ended).containsExactly(
            new RecordingOutbound.Ended(first.getConnectionId(), RealtimeCloseCode.REALTIME_CLOSE_CODE_SESSION_REPLACED.getNumber()),
            new RecordingOutbound.Ended(second.getConnectionId(), RealtimeCloseCode.REALTIME_CLOSE_CODE_SESSION_REPLACED.getNumber()));
        assertThat(registry.sessions()).extracting(LiveNetworkSession::instanceId).containsExactlyInAnyOrder("proxy-instance",
            "full-instance");

        outbound.sent.clear();
        hub.accept(core, capture("c1", "lobby"));
        assertThat(only(fullCredential, PayloadCase.REPLAY_CAPTURE_REQUEST).getReplayCaptureRequest().getRequestId()).isEqualTo("c1");
        assertThat(outbound.to(first.getConnectionId())).isEmpty();
        assertThat(outbound.to(second.getConnectionId())).isEmpty();
    }

    @Test
    void aReplacementIsAdmittedOnAFullNetworkAndANewNameIsNot() {
        for (int index = 0; index < NetworkLimits.MAX_MEMBERS; index++) {
            member(MinecraftCredential.instanceKey(), "instance-" + index, "server-" + index);
        }

        member(MinecraftCredential.instanceKey(), "restarted", "server-0");
        assertThat(hub.admit(socket(), unauthenticated(), principal(ServerRole.SERVER_ROLE_MEMBER, MinecraftCredential.instanceKey(),
            "extra", "server-extra"))).isEqualTo(NetworkHub.Admission.NETWORK_FULL);
    }

    @Test
    void aMemberThatIsAdmittedButNotYetGreetedReceivesNothing() {
        RealtimeConnectionState core = core("proxy-instance");
        RealtimePrincipal principal = principal(ServerRole.SERVER_ROLE_MEMBER,
            new MinecraftCredential(ServerKeyScope.BEHIND_PROXY, "lobby-key", "lobby"), "lobby-instance", "lobby");
        RealtimeConnectionState waiting = admitted(principal);

        hub.accept(core, moderation("e1", 1));
        hub.accept(core, RealtimeEnvelope.newBuilder().setStatWipePush(StatWipePushEvent.newBuilder()
            .addStatWipes(SyncPendingStatWipe.newBuilder().setMinecraftUuid(TARGET).setUsername("Steve").setPunishmentId("p1")))
            .build());
        hub.accept(core, capture("c1", "lobby"));
        assertThat(outbound.to(waiting.getConnectionId())).isEmpty();
        assertThat(registry.sessions()).extracting(LiveNetworkSession::name).doesNotContain("lobby");

        greet(waiting, principal, Topic.TOPIC_NETWORK_MODERATION, Topic.TOPIC_NETWORK_REPLAY_CAPTURE);
        assertThat(only(waiting, PayloadCase.MODERATION_STATE).getModerationState().getRevision()).isEqualTo(1);
    }

    @Test
    void aRestartedCoreRetiresItsPreviousSnapshotAtOnce() {
        RealtimeConnectionState before = core("proxy-before", "Proxy");
        RealtimeConnectionState otherProxy = core("other-proxy", "Proxy-2");
        RealtimeConnectionState member = member("lobby-key", "lobby");
        hub.accept(before, moderation("e1", 3));
        hub.accept(otherProxy, moderation("e9", 1));
        hub.onClosed(new RealtimeConnectionClosedEvent(before));
        hub.onClosed(new RealtimeConnectionClosedEvent(otherProxy));
        outbound.sent.clear();

        core("proxy-after", "PROXY");

        assertThat(outbound.to(member.getConnectionId())).singleElement().satisfies(envelope -> {
            assertThat(envelope.getModerationState().getSourceInstanceId()).isEqualTo("proxy-before");
            assertThat(envelope.getModerationState().getRetired()).isTrue();
        });
        RealtimeConnectionState late = member(MinecraftCredential.instanceKey(), "survival-instance", "survival");
        assertThat(outbound.to(late.getConnectionId())).extracting(envelope -> envelope.getModerationState().getSourceInstanceId())
            .containsExactly("other-proxy");
    }

    @Test
    void aReconnectingCoreKeepsItsSnapshot() {
        RealtimeConnectionState core = core("proxy-instance");
        RealtimeConnectionState member = member("lobby-key", "lobby");
        hub.accept(core, moderation("e1", 1));
        hub.onClosed(new RealtimeConnectionClosedEvent(core));
        core("proxy-instance");
        outbound.sent.clear();

        clock.advance(Duration.ofMinutes(5));
        hub.sweep();

        assertThat(outbound.to(member.getConnectionId())).isEmpty();
    }

    @Test
    void aMemberReportsOnlyItsPlayerCount() {
        core("proxy-instance");
        RealtimeConnectionState member = member("lobby-key", "lobby");

        assertThat(hub.accept(member, RealtimeEnvelope.newBuilder().setHostedPlayers(HostedPlayersEvent.newBuilder()
            .setSequence(1).setOnlineCount(3)).build())).isEqualTo(NetworkHub.Disposition.HANDLED);
        assertThat(registry.sessions()).filteredOn(session -> session.name().equals("lobby"))
            .extracting(LiveNetworkSession::onlinePlayers).containsExactly(3);
        assertThat(hub.accept(member, RealtimeEnvelope.newBuilder().setHostedPlayers(HostedPlayersEvent.newBuilder()
            .setSequence(2).addJoinedUuids(STAFF).setOnlineCount(4)).build())).isEqualTo(NetworkHub.Disposition.FORBIDDEN);
        assertThat(hub.accept(member, RealtimeEnvelope.newBuilder().setHostedPlayers(HostedPlayersEvent.newBuilder()
            .setSequence(3).addLeftUuids(STAFF).setOnlineCount(2)).build())).isEqualTo(NetworkHub.Disposition.FORBIDDEN);
    }

    @Test
    void hostedPlayersAreAppliedOnlyWhileTheCoreIsRegistered() {
        RealtimeConnectionState core = core("proxy-instance");
        List<String> applied = new ArrayList<>();
        registry.whileRegistered(core.getConnectionId(), () -> applied.add("before"));
        hub.onClosed(new RealtimeConnectionClosedEvent(core));
        registry.whileRegistered(core.getConnectionId(), () -> applied.add("after"));

        assertThat(applied).containsExactly("before");
    }

    private void accept(RealtimeConnectionState from, NetworkEnvelopeFixtures fixture) {
        assertThat(hub.accept(from, fixture.envelope())).isEqualTo(NetworkHub.Disposition.HANDLED);
    }

    private RealtimeConnectionState core(String instanceId) {
        return core(instanceId, "Proxy");
    }

    private RealtimeConnectionState core(String instanceId, String name) {
        return join(principal(ServerRole.SERVER_ROLE_CORE, MinecraftCredential.instanceKey(), instanceId, name),
            Topic.TOPIC_NETWORK_MEMBER_ACTIONS, Topic.TOPIC_NETWORK_REPLAY_CAPTURE, Topic.TOPIC_NETWORK_ROSTER);
    }

    private RealtimeConnectionState member(String keyId, String name) {
        return member(new MinecraftCredential(ServerKeyScope.BEHIND_PROXY, keyId, name), name + "-instance", name);
    }

    private RealtimeConnectionState member(MinecraftCredential credential, String instanceId, String name) {
        return join(principal(ServerRole.SERVER_ROLE_MEMBER, credential, instanceId, name),
            Topic.TOPIC_NETWORK_MODERATION, Topic.TOPIC_NETWORK_REPLAY_CAPTURE);
    }

    private RealtimeConnectionState join(RealtimePrincipal principal, Topic... topics) {
        RealtimeConnectionState state = admitted(principal);
        greet(state, principal, topics);
        return state;
    }

    private RealtimeConnectionState admitted(RealtimePrincipal principal) {
        RealtimeConnectionState state = unauthenticated();
        assertThat(hub.admit(socket(), state, principal)).isEqualTo(NetworkHub.Admission.ADMITTED);
        return state;
    }

    private void greet(RealtimeConnectionState state, RealtimePrincipal principal, Topic... topics) {
        state.authenticate(principal);
        for (Topic topic : topics) {
            state.subscribe(topic);
        }
        hub.welcome(state);
    }

    private static RealtimeConnectionState unauthenticated() {
        return new RealtimeConnectionState(new RealtimeOutbox(100, 1 << 20));
    }

    private static RealtimePrincipal principal(ServerRole role, MinecraftCredential credential, String instanceId, String name) {
        return RealtimePrincipal.minecraft(TestServers.server("Server", "owner@example.com"), instanceId, credential, role, name,
            "3.0.0");
    }

    private static WebSocketSession socket() {
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.isOpen()).thenReturn(true);
        return socket;
    }

    private RealtimeEnvelope only(RealtimeConnectionState to, PayloadCase payload) {
        List<RealtimeEnvelope> matching = outbound.to(to.getConnectionId()).stream()
            .filter(envelope -> envelope.getPayloadCase() == payload)
            .toList();
        assertThat(matching).hasSize(1);
        return matching.get(0);
    }

    private static ServerKey key(boolean allowProxyCommands) {
        return new ServerKey("lobby-key", "lobby", ServerKeyScope.BEHIND_PROXY, allowProxyCommands, "l".repeat(64), "abcdefgh",
            null, Instant.EPOCH, null);
    }

    private static MemberActionRequest action(MemberActionKind kind, String requestId) {
        return MemberActionRequest.newBuilder().setRequestId(requestId).setKind(kind).setActorUuid(STAFF).build();
    }

    private static RealtimeEnvelope envelope(MemberActionRequest request) {
        return RealtimeEnvelope.newBuilder().setMemberAction(request).build();
    }

    private static RealtimeEnvelope proxyCommand(String requestId) {
        return RealtimeEnvelope.newBuilder().setMemberAction(MemberActionRequest.newBuilder().setRequestId(requestId)
            .setKind(MemberActionKind.MEMBER_ACTION_KIND_PROXY_COMMAND).setCommandLine("stafflist")).build();
    }

    private static RealtimeEnvelope moderation(String epoch, long revision) {
        return RealtimeEnvelope.newBuilder().setModerationState(ModerationStateEvent.newBuilder()
            .setEpoch(epoch).setRevision(revision)
            .addFrozen(FrozenPlayer.newBuilder().setMinecraftUuid(TARGET).setStaffUuid(STAFF).setFrozenAt(1))).build();
    }

    private static RealtimeEnvelope capture(String requestId, String server) {
        return RealtimeEnvelope.newBuilder().setReplayCaptureRequest(ReplayCaptureRequest.newBuilder().setRequestId(requestId)
            .setTargetUuid(TARGET).setTargetName("Steve").setExpectedServer(server)).build();
    }
}
