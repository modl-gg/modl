package gg.modl.backend.network;

import build.buf.protovalidate.Validator;
import build.buf.protovalidate.ValidatorFactory;
import build.buf.protovalidate.exceptions.ValidationException;
import com.google.protobuf.Message;
import gg.modl.backend.realtime.auth.RealtimePrincipal;
import gg.modl.backend.realtime.lifecycle.RealtimeConnectionClosedEvent;
import gg.modl.backend.realtime.state.RealtimeConnectionState;
import gg.modl.backend.realtime.transport.RealtimeCloseStatuses;
import gg.modl.backend.serverkey.ServerKey;
import gg.modl.backend.serverkey.service.ServerKeyCache;
import gg.modl.proto.modl.v1.ErrorCode;
import gg.modl.proto.modl.v1.FeatureSettingsEvent;
import gg.modl.proto.modl.v1.HostedPlayersEvent;
import gg.modl.proto.modl.v1.MemberActionKind;
import gg.modl.proto.modl.v1.MemberActionRequest;
import gg.modl.proto.modl.v1.MemberActionResult;
import gg.modl.proto.modl.v1.MemberActionStatus;
import gg.modl.proto.modl.v1.ModerationStateEvent;
import gg.modl.proto.modl.v1.NetworkMember;
import gg.modl.proto.modl.v1.NetworkRosterEvent;
import gg.modl.proto.modl.v1.ProxyServersEvent;
import gg.modl.proto.modl.v1.RealtimeCloseCode;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import gg.modl.proto.modl.v1.RealtimeEnvelope.PayloadCase;
import gg.modl.proto.modl.v1.ReplayCaptureOutcome;
import gg.modl.proto.modl.v1.ReplayCaptureRequest;
import gg.modl.proto.modl.v1.ReplayCaptureResult;
import gg.modl.proto.modl.v1.ServerFeatureStatus;
import gg.modl.proto.modl.v1.StatWipePushEvent;
import gg.modl.proto.modl.v1.Topic;
import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

@Slf4j
@Component
public class NetworkHub {
    private static final Set<PayloadCase> NETWORK_PAYLOADS = EnumSet.of(
        PayloadCase.MODERATION_STATE,
        PayloadCase.MEMBER_ACTION,
        PayloadCase.MEMBER_ACTION_RESULT,
        PayloadCase.REPLAY_CAPTURE_REQUEST,
        PayloadCase.REPLAY_CAPTURE_RESULT,
        PayloadCase.HOSTED_PLAYERS,
        PayloadCase.NETWORK_ROSTER,
        PayloadCase.PROXY_SERVERS,
        PayloadCase.STAT_WIPE_PUSH,
        PayloadCase.FEATURE_STATUS);
    private static final String MODERATION_SLOT = "moderation:";
    private static final String FEATURE_SETTINGS_SLOT = "feature-settings";

    public enum Admission { ADMITTED, NETWORK_FULL, KEY_REVOKED }

    public enum Disposition { HANDLED, FORBIDDEN }

    private final Validator validator = ValidatorFactory.newBuilder().build();
    private final NetworkSessionRegistry registry;
    private final NetworkOutbound outbound;
    private final ModerationStateCache moderationStates;
    private final HostedPlayerIndex hostedPlayers;
    private final PendingMemberActions pendingActions;
    private final PendingReplayCaptures pendingCaptures;
    private final ServerKeyCache serverKeys;
    private final FeatureSettingsSource featureSettings;
    private final Clock clock;
    private final RateBuckets memberActionRate;
    private final RateBuckets proxyCommandRate;
    private final RateBuckets captureRequestRate;
    private final RateBuckets hostedPlayersRate;
    private final RateBuckets moderationStateRate;
    private final RateBuckets forwardedActionRate;
    private final RateBuckets proxyServersRate;
    private final RateBuckets featureStatusRate;
    private final AtomicBoolean rosterChanged = new AtomicBoolean();
    private final Object featureSettingsLock = new Object();
    private @Nullable FeatureSettingsEvent latestFeatureSettings;

    public NetworkHub(NetworkSessionRegistry registry, NetworkOutbound outbound, ModerationStateCache moderationStates,
                      HostedPlayerIndex hostedPlayers, PendingMemberActions pendingActions,
                      PendingReplayCaptures pendingCaptures, ServerKeyCache serverKeys,
                      FeatureSettingsSource featureSettings, Clock clock) {
        this.registry = registry;
        this.outbound = outbound;
        this.moderationStates = moderationStates;
        this.hostedPlayers = hostedPlayers;
        this.pendingActions = pendingActions;
        this.pendingCaptures = pendingCaptures;
        this.serverKeys = serverKeys;
        this.featureSettings = featureSettings;
        this.clock = clock;
        this.memberActionRate = new RateBuckets(NetworkLimits.MEMBER_ACTIONS_PER_SECOND, clock);
        this.proxyCommandRate = new RateBuckets(NetworkLimits.PROXY_COMMANDS_PER_SECOND, clock);
        this.captureRequestRate = new RateBuckets(NetworkLimits.CAPTURE_REQUESTS_PER_SECOND, clock);
        this.hostedPlayersRate = new RateBuckets(NetworkLimits.HOSTED_PLAYER_EVENTS_PER_SECOND, clock);
        this.forwardedActionRate = new RateBuckets(NetworkLimits.FORWARDED_ACTIONS_PER_CORE_PER_SECOND, clock);
        this.moderationStateRate = new RateBuckets(NetworkLimits.MODERATION_STATES_PER_SECOND, clock);
        this.proxyServersRate = new RateBuckets(NetworkLimits.PROXY_SERVER_REPORTS_PER_SECOND, clock);
        this.featureStatusRate = new RateBuckets(NetworkLimits.FEATURE_STATUS_REPORTS_PER_SECOND, clock);
    }

    public static boolean handles(PayloadCase payloadCase) {
        return NETWORK_PAYLOADS.contains(payloadCase);
    }

    public Admission admit(WebSocketSession socket, RealtimeConnectionState state, RealtimePrincipal principal) {
        NetworkSession session = new NetworkSession(socket, state, principal, clock.instant());
        NetworkSessionRegistry.Registration registration = registry.register(session);
        if (!registration.admitted()) {
            log.warn("Refused a network connection from {}: the network already has its maximum of servers", session.name());
            return Admission.NETWORK_FULL;
        }
        String keyId = session.keyId();
        if (keyId != null) {
            Optional<ServerKey> key = serverKeys.findById(keyId);
            if (key.isEmpty()) {
                registry.remove(session.connectionId());
                return Admission.KEY_REVOKED;
            }
            session.initializeProxyCommandsGrant(key.get().allowProxyCommands());
        }
        registration.replaced().forEach(this::replace);
        return Admission.ADMITTED;
    }

    public void welcome(RealtimeConnectionState state) {
        registry.get(state.getConnectionId()).ifPresent(session -> {
            session.welcome();
            publishFeatureSettings(currentFeatureSettings(), () -> List.of(session));
            if (session.isCore()) {
                retireSources(moderationStates.coreJoined(session.instanceId(), session.coreIdentity(), session.connectionId()));
                sendRoster(session, rosterEvent());
                return;
            }
            deliverCachedModeration(session);
            rosterChanged.set(true);
        });
    }

    @EventListener
    public void onClosed(RealtimeConnectionClosedEvent event) {
        registry.remove(event.state().getConnectionId()).ifPresent(this::forgetSession);
    }

    public Disposition accept(RealtimeConnectionState state, RealtimeEnvelope envelope) {
        Optional<NetworkSession> found = registry.get(state.getConnectionId());
        if (found.isEmpty()) {
            return Disposition.HANDLED;
        }
        NetworkSession from = found.get();
        return switch (envelope.getPayloadCase()) {
            case MODERATION_STATE -> fromCore(from, () -> acceptModeration(from, envelope.getModerationState()));
            case MEMBER_ACTION -> fromMember(from, () -> acceptMemberAction(from, envelope.getMemberAction()));
            case MEMBER_ACTION_RESULT -> fromCore(from, () -> acceptMemberActionResult(from, envelope.getMemberActionResult()));
            case REPLAY_CAPTURE_REQUEST -> fromCore(from, () -> acceptCaptureRequest(from, envelope.getReplayCaptureRequest()));
            case REPLAY_CAPTURE_RESULT -> fromMember(from, () -> acceptCaptureResult(from, envelope.getReplayCaptureResult()));
            case HOSTED_PLAYERS -> acceptHostedPlayers(from, envelope.getHostedPlayers());
            case STAT_WIPE_PUSH -> fromCore(from, () -> acceptStatWipes(envelope.getStatWipePush()));
            case PROXY_SERVERS -> fromCore(from, () -> acceptProxyServers(from, envelope.getProxyServers()));
            case FEATURE_STATUS -> acceptFeatureStatus(from, envelope.getFeatureStatus());
            default -> Disposition.FORBIDDEN;
        };
    }

    @EventListener
    public void onFeatureSettingsChanged(FeatureSettingsChanged changed) {
        publishFeatureSettings(changed.settings(), () -> Stream.concat(registry.cores().stream(), registry.members().stream()).toList());
    }

    public void revoke(ServerKey key) {
        for (NetworkSession session : registry.usingKey(key.id())) {
            outbound.close(session, ErrorCode.ERROR_CODE_UNAUTHORIZED, "The server key was revoked",
                RealtimeCloseStatuses.of(RealtimeCloseCode.REALTIME_CLOSE_CODE_AUTHENTICATION_FAILED, "Server key revoked"),
                "server_key_revoked");
        }
    }

    public void refreshGrants(ServerKey key) {
        for (NetworkSession session : registry.usingKey(key.id())) {
            session.changeProxyCommandsGrant(key.allowProxyCommands());
        }
    }

    @Scheduled(initialDelayString = "PT5S", fixedDelayString = "PT5S")
    public void sweep() {
        pendingActions.expire();
        for (PendingReplayCaptures.Pending timedOut : pendingCaptures.expire()) {
            answerCapture(timedOut.coreConnectionId(), timedOut.requestId(), ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_TIMED_OUT);
        }
        retireSources(moderationStates.retireDue());
    }

    @Scheduled(initialDelayString = "PT1S", fixedDelayString = "PT1S")
    public void publishRosterIfChanged() {
        if (!rosterChanged.getAndSet(false)) {
            return;
        }
        NetworkRosterEvent roster = rosterEvent();
        registry.cores().forEach(core -> sendRoster(core, roster));
    }

    private void replace(NetworkSession older) {
        log.warn("Another server connected to the modl network as {}; closed the older connection", older.name());
        outbound.end(older, RealtimeCloseStatuses.of(RealtimeCloseCode.REALTIME_CLOSE_CODE_SESSION_REPLACED,
            "Another server connected as " + older.name()), "session_replaced");
    }

    private void retireSources(List<String> sources) {
        for (String retiredSource : sources) {
            broadcastModeration(retiredSource, ModerationStateEvent.newBuilder()
                .setSourceInstanceId(retiredSource)
                .setRetired(true)
                .build());
        }
    }

    private Disposition fromCore(NetworkSession from, Runnable handler) {
        if (!from.isCore() || !from.holdsFullCredential()) {
            return Disposition.FORBIDDEN;
        }
        handler.run();
        return Disposition.HANDLED;
    }

    private Disposition fromMember(NetworkSession from, Runnable handler) {
        if (!from.isMember()) {
            return Disposition.FORBIDDEN;
        }
        handler.run();
        return Disposition.HANDLED;
    }

    private void acceptModeration(NetworkSession core, ModerationStateEvent submitted) {
        if (!isValid(submitted) || !moderationStateRate.tryAcquire(core.connectionId())) {
            return;
        }
        ModerationStateEvent stamped = submitted.toBuilder()
            .setSourceInstanceId(core.instanceId())
            .setRetired(false)
            .build();
        moderationStates.put(core.connectionId(), core.coreIdentity(), stamped);
        broadcastModeration(stamped.getSourceInstanceId(), stamped);
    }

    private void broadcastModeration(String source, ModerationStateEvent state) {
        byte[] payload = outbound.prepare(RealtimeEnvelope.newBuilder().setModerationState(state).build());
        for (NetworkSession member : registry.members()) {
            if (member.subscribedTo(Topic.TOPIC_NETWORK_MODERATION)) {
                outbound.sendLatest(member, MODERATION_SLOT + source, payload);
            }
        }
    }

    private void deliverCachedModeration(NetworkSession member) {
        if (!member.subscribedTo(Topic.TOPIC_NETWORK_MODERATION)) {
            return;
        }
        for (ModerationStateEvent state : moderationStates.current()) {
            outbound.sendLatest(member, MODERATION_SLOT + state.getSourceInstanceId(),
                outbound.prepare(RealtimeEnvelope.newBuilder().setModerationState(state).build()));
        }
    }

    private void acceptMemberAction(NetworkSession member, MemberActionRequest submitted) {
        if (!isValid(submitted)) {
            answerMember(member, submitted.getRequestId(), MemberActionStatus.MEMBER_ACTION_STATUS_INVALID, actorOf(submitted));
            return;
        }
        boolean proxyCommand = submitted.getKind() == MemberActionKind.MEMBER_ACTION_KIND_PROXY_COMMAND;
        if (!memberActionRate.tryAcquire(member.connectionId())
            || (proxyCommand && !proxyCommandRate.tryAcquire(member.connectionId()))) {
            answerMember(member, submitted.getRequestId(), MemberActionStatus.MEMBER_ACTION_STATUS_RATE_LIMITED, actorOf(submitted));
            return;
        }
        if (proxyCommand && !member.proxyCommandsGranted()) {
            answerMember(member, submitted.getRequestId(), MemberActionStatus.MEMBER_ACTION_STATUS_NOT_PERMITTED, actorOf(submitted));
            return;
        }
        MemberActionRequest stamped = submitted.toBuilder()
            .setMemberName(member.name())
            .setMemberInstanceId(member.instanceId())
            .setProxyCommandGranted(member.proxyCommandsGranted())
            .build();
        Optional<NetworkSession> core = coreFor(actorOf(submitted));
        if (core.isEmpty()) {
            answerMember(member, submitted.getRequestId(), MemberActionStatus.MEMBER_ACTION_STATUS_NO_CORE, actorOf(submitted));
            return;
        }
        if (!forwardedActionRate.tryAcquire(core.get().connectionId())
            || !pendingActions.track(submitted.getRequestId(), member.connectionId(), core.get().connectionId(), actorOf(submitted))) {
            answerMember(member, submitted.getRequestId(), MemberActionStatus.MEMBER_ACTION_STATUS_RATE_LIMITED, actorOf(submitted));
            return;
        }
        outbound.send(core.get(), RealtimeEnvelope.newBuilder().setMemberAction(stamped).build());
    }

    private Optional<NetworkSession> coreFor(@Nullable String actorUuid) {
        List<NetworkSession> candidates = registry.cores().stream()
            .filter(core -> core.subscribedTo(Topic.TOPIC_NETWORK_MEMBER_ACTIONS))
            .toList();
        if (actorUuid != null) {
            Optional<String> hosting = hostedPlayers.coreHosting(actorUuid,
                candidates.stream().map(NetworkSession::connectionId).toList());
            if (hosting.isPresent()) {
                return registry.get(hosting.get());
            }
        }
        return candidates.size() == 1 ? Optional.of(candidates.get(0)) : Optional.empty();
    }

    private void acceptMemberActionResult(NetworkSession core, MemberActionResult result) {
        if (!isValid(result)) {
            return;
        }
        pendingActions.complete(result.getRequestId(), core.connectionId())
            .ifPresent(pending -> registry.get(pending.memberConnectionId())
                .ifPresent(member -> answerMember(member, pending.requestId(), result.getStatus(), pending.actorUuid())));
    }

    private void answerMember(NetworkSession member, String requestId, MemberActionStatus status, @Nullable String actorUuid) {
        if (requestId.isEmpty()) {
            return;
        }
        MemberActionResult.Builder result = MemberActionResult.newBuilder().setRequestId(requestId).setStatus(status);
        if (actorUuid != null) {
            result.setActorUuid(actorUuid);
        }
        outbound.send(member, RealtimeEnvelope.newBuilder().setMemberActionResult(result).build());
    }

    @Nullable
    private static String actorOf(MemberActionRequest request) {
        return request.hasActorUuid() ? request.getActorUuid() : null;
    }

    private void acceptCaptureRequest(NetworkSession core, ReplayCaptureRequest submitted) {
        if (!isValid(submitted) || !captureRequestRate.tryAcquire(core.connectionId())) {
            answerCapture(core.connectionId(), submitted.getRequestId(), ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_ERROR);
            return;
        }
        Optional<NetworkSession> member = submitted.hasExpectedServer()
            ? registry.memberNamed(submitted.getExpectedServer())
                .filter(candidate -> candidate.subscribedTo(Topic.TOPIC_NETWORK_REPLAY_CAPTURE))
            : Optional.empty();
        if (member.isEmpty()) {
            answerCapture(core.connectionId(), submitted.getRequestId(), ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_NO_MEMBER);
            return;
        }
        if (!pendingCaptures.track(submitted.getRequestId(), core.connectionId(), member.get().connectionId())) {
            answerCapture(core.connectionId(), submitted.getRequestId(), ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_ERROR);
            return;
        }
        ReplayCaptureRequest stamped = submitted.toBuilder().setRequesterInstanceId(core.instanceId()).build();
        outbound.send(member.get(), RealtimeEnvelope.newBuilder().setReplayCaptureRequest(stamped).build());
    }

    private void acceptCaptureResult(NetworkSession member, ReplayCaptureResult result) {
        if (!isValid(result)) {
            return;
        }
        pendingCaptures.complete(result.getRequestId(), member.connectionId())
            .flatMap(pending -> registry.get(pending.coreConnectionId()))
            .ifPresent(core -> outbound.send(core, RealtimeEnvelope.newBuilder().setReplayCaptureResult(result).build()));
    }

    private void answerCapture(String coreConnectionId, String requestId, ReplayCaptureOutcome outcome) {
        if (requestId.isEmpty()) {
            return;
        }
        registry.get(coreConnectionId).ifPresent(core -> outbound.send(core, RealtimeEnvelope.newBuilder()
            .setReplayCaptureResult(ReplayCaptureResult.newBuilder().setRequestId(requestId).setOutcome(outcome))
            .build()));
    }

    private Disposition acceptHostedPlayers(NetworkSession from, HostedPlayersEvent event) {
        if (from.isMember() && (event.getJoinedUuidsCount() > 0 || event.getLeftUuidsCount() > 0)) {
            return Disposition.FORBIDDEN;
        }
        if (!isValid(event) || !hostedPlayersRate.tryAcquire(from.connectionId())) {
            return Disposition.HANDLED;
        }
        if (from.isCore()) {
            registry.whileRegistered(from.connectionId(), () -> hostedPlayers.apply(from.connectionId(), event));
        }
        if (from.reportOnlinePlayers(event.getOnlineCount()) && from.isMember()) {
            rosterChanged.set(true);
        }
        return Disposition.HANDLED;
    }

    private void acceptStatWipes(StatWipePushEvent event) {
        if (event.getStatWipesCount() == 0) {
            return;
        }
        byte[] payload = outbound.prepare(RealtimeEnvelope.newBuilder().setStatWipePush(event).build());
        registry.members().forEach(member -> outbound.send(member, payload));
    }

    private void acceptProxyServers(NetworkSession core, ProxyServersEvent event) {
        if (!isValid(event) || !proxyServersRate.tryAcquire(core.connectionId())) {
            return;
        }
        core.reportProxyServers(event.getServersList().stream()
            .map(server -> new ProxyServer(server.getName(), server.getAddress()))
            .toList());
    }

    private Disposition acceptFeatureStatus(NetworkSession from, ServerFeatureStatus reported) {
        if (isValid(reported) && featureStatusRate.tryAcquire(from.connectionId())) {
            from.reportFeatures(FeatureStatus.of(reported));
        }
        return Disposition.HANDLED;
    }

    private @Nullable FeatureSettingsEvent currentFeatureSettings() {
        try {
            return featureSettings.current();
        } catch (RuntimeException e) {
            log.warn("Could not read the feature settings for a welcomed server, it gets the last known ones", e);
            return null;
        }
    }

    private void publishFeatureSettings(@Nullable FeatureSettingsEvent candidate, Supplier<List<NetworkSession>> targets) {
        synchronized (featureSettingsLock) {
            FeatureSettingsEvent known = latestFeatureSettings;
            FeatureSettingsEvent newest = candidate != null && (known == null || candidate.getVersion() >= known.getVersion())
                ? candidate
                : known;
            if (newest == null) {
                return;
            }
            latestFeatureSettings = newest;
            byte[] payload = outbound.prepare(RealtimeEnvelope.newBuilder().setFeatureSettings(newest).build());
            targets.get().forEach(session -> sendFeatureSettings(session, payload));
        }
    }

    private void sendFeatureSettings(NetworkSession session, byte[] payload) {
        if (session.subscribedTo(Topic.TOPIC_MINECRAFT_FEATURE_SETTINGS)) {
            outbound.sendLatest(session, FEATURE_SETTINGS_SLOT, payload);
        }
    }

    private void forgetSession(NetworkSession session) {
        memberActionRate.forget(session.connectionId());
        proxyCommandRate.forget(session.connectionId());
        captureRequestRate.forget(session.connectionId());
        hostedPlayersRate.forget(session.connectionId());
        moderationStateRate.forget(session.connectionId());
        forwardedActionRate.forget(session.connectionId());
        proxyServersRate.forget(session.connectionId());
        featureStatusRate.forget(session.connectionId());
        if (session.isCore()) {
            moderationStates.coreClosed(session.connectionId());
            hostedPlayers.forget(session.connectionId());
            pendingCaptures.coreClosed(session.connectionId());
            for (PendingMemberActions.Pending orphaned : pendingActions.coreClosed(session.connectionId())) {
                registry.get(orphaned.memberConnectionId()).ifPresent(member -> answerMember(member, orphaned.requestId(),
                    MemberActionStatus.MEMBER_ACTION_STATUS_NO_CORE, orphaned.actorUuid()));
            }
            return;
        }
        pendingActions.memberClosed(session.connectionId());
        for (PendingReplayCaptures.Pending orphaned : pendingCaptures.memberClosed(session.connectionId())) {
            answerCapture(orphaned.coreConnectionId(), orphaned.requestId(),
                ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_MEMBER_DISCONNECTED);
        }
        rosterChanged.set(true);
    }

    private NetworkRosterEvent rosterEvent() {
        NetworkRosterEvent.Builder roster = NetworkRosterEvent.newBuilder();
        for (NetworkSession member : registry.members()) {
            roster.addMembers(NetworkMember.newBuilder()
                .setInstanceId(member.instanceId())
                .setName(member.name())
                .setRole(member.role())
                .setPluginVersion(member.pluginVersion())
                .setOnlinePlayers(member.onlinePlayers()));
        }
        return roster.build();
    }

    private void sendRoster(NetworkSession core, NetworkRosterEvent roster) {
        if (core.subscribedTo(Topic.TOPIC_NETWORK_ROSTER)) {
            outbound.send(core, RealtimeEnvelope.newBuilder().setNetworkRoster(roster).build());
        }
    }

    private boolean isValid(Message message) {
        try {
            return validator.validate(message).isSuccess();
        } catch (ValidationException invalidRules) {
            log.warn("Could not validate a {} from a network connection", message.getDescriptorForType().getName(), invalidRules);
            return false;
        }
    }
}
