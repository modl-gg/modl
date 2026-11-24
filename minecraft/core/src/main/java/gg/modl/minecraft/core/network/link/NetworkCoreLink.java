package gg.modl.minecraft.core.network.link;

import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.PluginServices;
import gg.modl.minecraft.core.network.ActionOrigin;
import gg.modl.minecraft.core.network.MemberAction;
import gg.modl.minecraft.core.network.MemberActionExecutor;
import gg.modl.minecraft.core.network.ModerationPublisher;
import gg.modl.minecraft.core.network.ModerationSnapshot;
import gg.modl.minecraft.core.network.ModerationStateSink;
import gg.modl.minecraft.core.network.ProxyServerNames;
import gg.modl.minecraft.core.network.SwitchableNetworkAvailability;
import gg.modl.minecraft.core.realtime.ConnectionListener;
import gg.modl.minecraft.core.realtime.RealtimeConnection;
import gg.modl.minecraft.core.realtime.TerminationReason;
import gg.modl.minecraft.core.service.sync.SyncService;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.core.util.Scheduling;
import gg.modl.proto.modl.v1.MemberActionRequest;
import gg.modl.proto.modl.v1.NetworkRosterEvent;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import gg.modl.proto.modl.v1.RealtimeEnvelope.PayloadCase;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class NetworkCoreLink implements ModerationStateSink, ConnectionListener {
    private static final String THREAD_NAME = "modl-network";

    private final RealtimeConnection connection;
    private final MemberActionExecutor executor;
    private final ModerationPublisher publisher;
    private final SwitchableNetworkAvailability availability;
    private final Platform platform;
    private final ProxyServerNames serverNames;
    private final NetworkRoster roster = new NetworkRoster();
    private final ScheduledExecutorService scheduler;
    private final HostedPlayersReporter hostedPlayers;
    private final PluginLogger logger;
    private volatile @Nullable NetworkReplayService replayService;
    private volatile @Nullable ModerationSnapshot latest;

    @Builder
    private NetworkCoreLink(RealtimeConnection connection, MemberActionExecutor executor, ModerationPublisher publisher,
                            SwitchableNetworkAvailability availability, Platform platform, PluginLogger logger) {
        this.connection = connection;
        this.executor = executor;
        this.publisher = publisher;
        this.availability = availability;
        this.platform = platform;
        this.logger = logger;
        this.serverNames = new ProxyServerNames(platform::knownServerNames, logger);
        ScheduledThreadPoolExecutor networkThread = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
        networkThread.setRemoveOnCancelPolicy(true);
        this.scheduler = networkThread;
        this.hostedPlayers = new HostedPlayersReporter(this::onlinePlayers, connection::send, logger);
        connection.register(PayloadCase.MEMBER_ACTION, envelope -> onMemberAction(envelope.getMemberAction()));
        connection.register(PayloadCase.NETWORK_ROSTER, envelope -> onRoster(envelope.getNetworkRoster()));
        connection.register(PayloadCase.REPLAY_CAPTURE_RESULT, envelope -> {
            NetworkReplayService replays = replayService;
            if (replays != null) replays.onResult(envelope.getReplayCaptureResult());
        });
        connection.addListener(this);
    }

    public Runnable install(SyncService syncService, PluginServices services) {
        NetworkReplayService replays = new NetworkReplayService(platform, roster, connection::send, scheduler);
        replayService = replays;
        services.setReplayService(replays);
        syncService.setStatWipeExecutor(new NetworkStatWipeExecutor(roster, connection::send));
        Runnable stopUsingRoster = availability.use(roster);
        publisher.addSink(this);
        Scheduling.withFixedDelay(scheduler, hostedPlayers::flush, HostedPlayersReporter.FLUSH_INTERVAL_MILLIS,
                HostedPlayersReporter.FLUSH_INTERVAL_MILLIS, TimeUnit.MILLISECONDS, logger);
        return () -> {
            publisher.removeSink(this);
            stopUsingRoster.run();
            syncService.setStatWipeExecutor(null);
            services.setReplayService(null);
            scheduler.shutdownNow();
            replays.disconnected();
        };
    }

    @Override
    public void publish(ModerationSnapshot snapshot) {
        latest = snapshot;
        sendModeration(snapshot);
    }

    @Override
    public void onConnected(boolean first) {
        ModerationSnapshot snapshot = latest;
        if (snapshot != null) sendModeration(snapshot);
        submit(hostedPlayers::connected);
    }

    @Override
    public void onDisconnected() {
        submit(hostedPlayers::disconnected);
        roster.clear();
        NetworkReplayService replays = replayService;
        if (replays != null) replays.disconnected();
    }

    @Override
    public void onResyncAdvised() {
    }

    @Override
    public void onReplaced() {
    }

    @Override
    public void onTerminated(TerminationReason reason) {
    }

    void onMemberAction(MemberActionRequest request) {
        MemberAction action = MemberActionProtos.fromProto(request);
        executor.submit(action, ActionOrigin.NETWORK, outcome -> connection.send(RealtimeEnvelope.newBuilder()
                .setMemberActionResult(MemberActionProtos.toResult(request.getRequestId(), outcome, action.getActor()))
                .build()));
    }

    void onRoster(NetworkRosterEvent event) {
        roster.replace(event);
        for (String name : roster.memberNames()) serverNames.warnOnceIfUnknown(name);
    }

    NetworkRoster roster() {
        return roster;
    }

    private void sendModeration(ModerationSnapshot snapshot) {
        connection.send(RealtimeEnvelope.newBuilder()
                .setModerationState(ModerationStateProtos.toProto(snapshot))
                .build());
    }

    private Collection<UUID> onlinePlayers() {
        List<UUID> online = new ArrayList<>();
        platform.getOnlinePlayers().forEach(player -> online.add(player.getUuid()));
        return online;
    }

    private void submit(Runnable task) {
        try {
            scheduler.execute(task);
        } catch (RejectedExecutionException stopped) {
            return;
        }
    }
}
