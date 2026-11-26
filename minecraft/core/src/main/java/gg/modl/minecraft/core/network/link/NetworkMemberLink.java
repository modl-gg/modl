package gg.modl.minecraft.core.network.link;

import gg.modl.minecraft.api.http.response.StartupResponse;
import gg.modl.minecraft.core.connection.PanelLocations;
import gg.modl.minecraft.core.connection.WikiPages;
import gg.modl.minecraft.core.member.MemberSession;
import gg.modl.minecraft.core.feature.FeatureSettingsChannel;
import gg.modl.minecraft.core.network.MemberAction;
import gg.modl.minecraft.core.network.MemberActionKind;
import gg.modl.minecraft.core.network.MemberActionOutcome;
import gg.modl.minecraft.core.network.MemberActionSender;
import gg.modl.minecraft.core.network.MemberStatWipes;
import gg.modl.minecraft.core.network.ModerationSnapshotListener;
import gg.modl.minecraft.core.network.ReplayCaptureHandler;
import gg.modl.minecraft.core.realtime.ConnectionListener;
import gg.modl.minecraft.core.realtime.RealtimeConnection;
import gg.modl.minecraft.core.realtime.RealtimeTopics;
import gg.modl.minecraft.core.realtime.TerminationReason;
import gg.modl.minecraft.core.service.ReplayCaptureResult;
import gg.modl.minecraft.core.service.ReplayCaptureStatus;
import gg.modl.minecraft.core.service.ReplayRecordingOutcome;
import gg.modl.minecraft.core.service.ReplayRecordingResult;
import gg.modl.minecraft.core.util.Futures;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.proto.modl.v1.MemberActionResult;
import gg.modl.proto.modl.v1.ModerationStateEvent;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import gg.modl.proto.modl.v1.RealtimeEnvelope.PayloadCase;
import gg.modl.proto.modl.v1.ReplayCaptureOutcome;
import gg.modl.proto.modl.v1.ReplayCaptureRequest;
import gg.modl.proto.modl.v1.ServerRole;
import gg.modl.proto.modl.v1.StatWipePushEvent;
import gg.modl.proto.modl.v1.SyncPendingStatWipe;
import gg.modl.proto.modl.v1.Topic;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

public final class NetworkMemberLink implements MemberActionSender {
    static final long ACTION_TIMEOUT_MILLIS = 10_000;
    static final long LOST_NOTICE_DELAY_MILLIS = 10_000;
    static final long FIRST_CONNECT_NOTICE_DELAY_MILLIS = 15_000;
    static final int MAX_PENDING_ACTIONS = 256;
    private static final String THREAD_NAME = "modl-network";
    private static final String HUB_SOURCE_PREFIX = "hub:";

    private final ModerationSnapshotListener moderation;
    private final ReplayCaptureHandler captures;
    private final MemberStatWipes statWipes;
    private final FeatureSettingsChannel featureSettings;
    private final IntSupplier onlinePlayerCount;
    private final Supplier<String> serverName;
    private final Runnable credentialsRejected;
    private final String configPath;
    private final PluginLogger logger;
    private final boolean debugMode;
    private final ScheduledExecutorService scheduler;
    private final Map<String, PendingAction> pending = new ConcurrentHashMap<>();
    private volatile @Nullable Connection current;

    @Builder
    private NetworkMemberLink(ModerationSnapshotListener moderation, ReplayCaptureHandler captures, MemberStatWipes statWipes,
                              FeatureSettingsChannel featureSettings, IntSupplier onlinePlayerCount, Supplier<String> serverName,
                              Runnable credentialsRejected, String configPath, PluginLogger logger, boolean debugMode) {
        this.moderation = moderation;
        this.captures = captures;
        this.statWipes = statWipes;
        this.featureSettings = featureSettings;
        this.onlinePlayerCount = onlinePlayerCount;
        this.serverName = serverName;
        this.credentialsRejected = credentialsRejected;
        this.configPath = configPath;
        this.logger = logger;
        this.debugMode = debugMode;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
    }

    static String connectedNotice(String url, String name) {
        String named = name == null || name.isEmpty() ? "" : " as " + name;
        return "[network] Connected to the modl network at " + url + named + ".";
    }

    static String lostNotice() {
        return "[network] Lost the connection to the modl network; keeping the current staff state and reconnecting.";
    }

    static String websocketNotice(String url, String cause) {
        return "[network] The modl server at " + url + " accepted the key but its websocket could not be opened ("
                + cause + "). If a reverse proxy sits in front of the panel, allow websocket upgrades (see "
                + WikiPages.HTTPS_AND_REVERSE_PROXY + ").";
    }

    static String replacedNotice(String name) {
        String named = name == null || name.isEmpty() ? "" : " as " + name;
        return "[network] Another server connected to the modl network" + named + " with this server key, so this server was "
                + "disconnected; reconnecting in " + TimeUnit.MILLISECONDS.toSeconds(RealtimeConnection.SESSION_REPLACED_RECONNECT_DELAY_MS)
                + " seconds. Give every server its own key (" + PanelLocations.ADD_SERVER + ").";
    }

    static String rejectedKeyNotice(String configPath) {
        return "[network] The modl server rejected the server key; retrying. Check remote.api-key in " + configPath + ".";
    }

    static String proxyCommandsDisabledNotice() {
        return "[network] /proxycmd is disabled for this server's key; enable it in " + PanelLocations.MINECRAFT_SERVERS + ".";
    }

    public synchronized void connect(MemberSession session) {
        disconnect();
        StartupResponse startup = session.getStartup();
        Set<Topic> topics = RealtimeTopics.union(RealtimeTopics.memberNetworkTopicsGrantedBy(startup),
                RealtimeTopics.featureTopicsGrantedBy(startup));
        if (topics.isEmpty()) return;
        RealtimeConnection connection = new RealtimeConnection(session.getConnections(), protocolVersion(startup), topics,
                serverName, startup.getServerInstanceId(), ServerRole.SERVER_ROLE_MEMBER, logger, debugMode);
        Connection created = new Connection(connection, session.getConnections().baseUrl());
        connection.register(PayloadCase.MODERATION_STATE, envelope -> onModeration(envelope.getModerationState()));
        connection.register(PayloadCase.MEMBER_ACTION_RESULT, envelope -> onResult(envelope.getMemberActionResult()));
        connection.register(PayloadCase.REPLAY_CAPTURE_REQUEST, envelope -> onReplayRequest(envelope.getReplayCaptureRequest(), connection::send));
        connection.register(PayloadCase.STAT_WIPE_PUSH, envelope -> onStatWipes(envelope.getStatWipePush()));
        featureSettings.attachTo(connection);
        connection.addListener(created);
        current = created;
        created.start();
    }

    public synchronized void disconnect() {
        Connection ended = current;
        current = null;
        if (ended == null) return;
        ended.stop();
        failPending(MemberActionOutcome.NO_CORE);
        moderation.linkDown();
    }

    public void shutdown() {
        disconnect();
        scheduler.shutdownNow();
    }

    public boolean isConnected() {
        Connection connection = current;
        return connection != null && connection.realtime.isConnected();
    }

    @Override
    public void send(MemberAction action, Consumer<MemberActionOutcome> onResult) {
        Connection connection = current;
        if (connection == null || !connection.realtime.isConnected() || pending.size() >= MAX_PENDING_ACTIONS) {
            onResult.accept(MemberActionOutcome.NO_CORE);
            return;
        }
        PendingAction waiting = new PendingAction(action, onResult);
        pending.put(action.getRequestId(), waiting);
        waiting.timeout = schedule(() -> complete(action.getRequestId(), MemberActionOutcome.NO_CORE), ACTION_TIMEOUT_MILLIS);
        if (!connection.realtime.send(RealtimeEnvelope.newBuilder()
                .setMemberAction(MemberActionProtos.toProto(action))
                .build())) {
            complete(action.getRequestId(), MemberActionOutcome.NO_CORE);
        }
    }

    void onModeration(ModerationStateEvent event) {
        String source = HUB_SOURCE_PREFIX + event.getSourceInstanceId();
        if (event.getRetired()) moderation.retire(source);
        else moderation.accept(source, ModerationStateProtos.fromProto(event));
    }

    void onResult(MemberActionResult result) {
        MemberActionOutcome outcome = MemberActionProtos.outcomeOf(result.getStatus());
        PendingAction waiting = pending.get(result.getRequestId());
        if (outcome == MemberActionOutcome.NOT_PERMITTED && waiting != null
                && waiting.action.getKind() == MemberActionKind.PROXY_COMMAND) {
            logger.warning(proxyCommandsDisabledNotice());
        }
        complete(result.getRequestId(), outcome);
    }

    void onStatWipes(StatWipePushEvent push) {
        for (SyncPendingStatWipe wipe : push.getStatWipesList()) {
            UUID uuid = Uuids.parse(wipe.getMinecraftUuid());
            if (uuid == null) {
                logger.warning("[stat-wipe] Ignored a stat wipe for an invalid player id from the modl network");
                continue;
            }
            statWipes.execute(wipe.getUsername(), uuid, wipe.getPunishmentId());
        }
    }

    static ReplayCaptureOutcome outcomeOf(ReplayCaptureStatus status) {
        if (status == null) return ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_ERROR;
        switch (status) {
            case OK:
                return ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_OK;
            case NO_ACTIVE_RECORDING:
                return ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_NO_ACTIVE_RECORDING;
            case NOT_LOCAL:
                return ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_NOT_HOSTED;
            default:
                return ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_ERROR;
        }
    }

    void onReplayRequest(ReplayCaptureRequest request, Consumer<RealtimeEnvelope> reply) {
        UUID target = Uuids.parse(request.getTargetUuid());
        String requestId = request.getRequestId();
        if (target == null) {
            answerCapture(reply, requestId, ReplayCaptureResult.error());
            return;
        }
        String name = request.getTargetName();
        switch (request.getAction()) {
            case REPLAY_REQUEST_ACTION_CAPTURE:
                Futures.whenComplete(captures.capture(target, name), (result, failure) -> answerCapture(reply, requestId,
                        failure != null || result == null ? ReplayCaptureResult.error() : result), logger);
                return;
            case REPLAY_REQUEST_ACTION_START_RECORDING:
                answerRecording(reply, requestId, captures.startRecording(target, name));
                return;
            case REPLAY_REQUEST_ACTION_STOP_RECORDING:
                answerRecording(reply, requestId, captures.stopRecording(target, name));
                return;
            case REPLAY_REQUEST_ACTION_RECORDING_STATUS:
                answerRecording(reply, requestId, captures.recordingStatus(target, name));
                return;
            default:
                answerCapture(reply, requestId, ReplayCaptureResult.error());
        }
    }

    private static void answerCapture(Consumer<RealtimeEnvelope> reply, String requestId, ReplayCaptureResult result) {
        gg.modl.proto.modl.v1.ReplayCaptureResult.Builder answer = gg.modl.proto.modl.v1.ReplayCaptureResult.newBuilder()
                .setRequestId(requestId)
                .setOutcome(outcomeOf(result.getStatus()));
        String replayId = result.getReplayId();
        if (replayId != null) answer.setReplayId(replayId);
        reply.accept(RealtimeEnvelope.newBuilder().setReplayCaptureResult(answer).build());
    }

    private void answerRecording(Consumer<RealtimeEnvelope> reply, String requestId,
                                 CompletableFuture<ReplayRecordingResult> pending) {
        Futures.whenComplete(pending, (result, failure) -> {
            ReplayRecordingResult answer = failure != null || result == null
                    ? ReplayRecordingResult.of(ReplayRecordingOutcome.ERROR) : result;
            reply.accept(RealtimeEnvelope.newBuilder()
                    .setReplayCaptureResult(ReplayRecordingProtos.toResult(requestId, answer)).build());
        }, logger);
    }

    private void complete(String requestId, MemberActionOutcome outcome) {
        PendingAction waiting = pending.remove(requestId);
        if (waiting == null) return;
        ScheduledFuture<?> timeout = waiting.timeout;
        if (timeout != null) timeout.cancel(false);
        waiting.onResult.accept(outcome);
    }

    private void failPending(MemberActionOutcome outcome) {
        List<String> requestIds = new ArrayList<>(pending.keySet());
        for (String requestId : requestIds) complete(requestId, outcome);
    }

    private @Nullable ScheduledFuture<?> schedule(Runnable task, long delayMillis) {
        try {
            return scheduler.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException stopped) {
            return null;
        }
    }

    private static int protocolVersion(StartupResponse startup) {
        Integer advertised = startup.getRealtimeProtocolVersion();
        return advertised != null ? advertised : 1;
    }

    private final class Connection implements ConnectionListener {
        private final RealtimeConnection realtime;
        private final String url;
        private final HostedPlayerCountReporter hosted;
        private @Nullable ScheduledFuture<?> hostedFlush;
        private @Nullable ScheduledFuture<?> lostNotice;
        private @Nullable ScheduledFuture<?> firstConnectNotice;
        private boolean announced;
        private volatile boolean stopped;

        private Connection(RealtimeConnection realtime, String url) {
            this.realtime = realtime;
            this.url = url;
            this.hosted = new HostedPlayerCountReporter(onlinePlayerCount, realtime::send);
        }

        private void start() {
            firstConnectNotice = schedule(() -> {
                if (!stopped && !announced) logger.warning(websocketNotice(url, "no answer within 15 seconds"));
            }, FIRST_CONNECT_NOTICE_DELAY_MILLIS);
            hostedFlush = scheduleRepeating(hosted::flush);
            realtime.start();
        }

        private void stop() {
            stopped = true;
            cancel(firstConnectNotice);
            cancel(lostNotice);
            cancel(hostedFlush);
            realtime.stop();
        }

        @Override
        public void onConnected(boolean first) {
            if (stopped) return;
            cancel(firstConnectNotice);
            cancel(lostNotice);
            if (!announced) {
                announced = true;
                logger.info(connectedNotice(url, serverName.get()));
            }
            moderation.linkUp();
            submit(hosted::connected);
        }

        @Override
        public void onDisconnected() {
            if (stopped) return;
            moderation.linkDown();
            submit(hosted::disconnected);
            failPending(MemberActionOutcome.NO_CORE);
            cancel(lostNotice);
            lostNotice = schedule(() -> {
                if (stopped || realtime.isConnected()) return;
                announced = false;
                logger.warning(lostNotice());
            }, LOST_NOTICE_DELAY_MILLIS);
        }

        @Override
        public void onResyncAdvised() {
        }

        @Override
        public void onReplaced() {
            if (stopped) return;
            cancel(lostNotice);
            announced = false;
            logger.warning(replacedNotice(serverName.get()));
        }

        @Override
        public void onTerminated(TerminationReason reason) {
            if (stopped || reason != TerminationReason.CREDENTIALS_REJECTED) return;
            logger.warning(rejectedKeyNotice(configPath));
            credentialsRejected.run();
        }

        private @Nullable ScheduledFuture<?> scheduleRepeating(Runnable task) {
            try {
                return scheduler.scheduleWithFixedDelay(task, HostedPlayersReporter.FLUSH_INTERVAL_MILLIS,
                        HostedPlayersReporter.FLUSH_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException stopping) {
                return null;
            }
        }

        private void submit(Runnable task) {
            try {
                scheduler.execute(task);
            } catch (RejectedExecutionException stopping) {
                logger.debug("[network] Dropped a link task because the scheduler is stopping");
            }
        }

        private void cancel(@Nullable ScheduledFuture<?> task) {
            if (task != null) task.cancel(false);
        }
    }

    private static final class PendingAction {
        private final MemberAction action;
        private final Consumer<MemberActionOutcome> onResult;
        private volatile @Nullable ScheduledFuture<?> timeout;

        private PendingAction(MemberAction action, Consumer<MemberActionOutcome> onResult) {
            this.action = action;
            this.onResult = onResult;
        }
    }
}
