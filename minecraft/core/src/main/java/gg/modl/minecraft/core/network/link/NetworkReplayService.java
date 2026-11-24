package gg.modl.minecraft.core.network.link;

import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.service.ReplayCaptureResult;
import gg.modl.minecraft.core.service.ReplayCaptureStatus;
import gg.modl.minecraft.core.service.ReplayService;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import gg.modl.proto.modl.v1.ReplayCaptureRequest;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

public final class NetworkReplayService implements ReplayService {
    static final long CAPTURE_TIMEOUT_SECONDS = 600;

    private final Platform platform;
    private final NetworkRoster roster;
    private final Predicate<RealtimeEnvelope> sender;
    private final ScheduledExecutorService scheduler;
    private final Map<String, PendingCapture> byRequestId = new ConcurrentHashMap<>();
    private final Map<UUID, PendingCapture> byTarget = new ConcurrentHashMap<>();

    NetworkReplayService(Platform platform, NetworkRoster roster, Predicate<RealtimeEnvelope> sender,
                         ScheduledExecutorService scheduler) {
        this.platform = platform;
        this.roster = roster;
        this.sender = sender;
        this.scheduler = scheduler;
    }

    @Override
    public CompletableFuture<ReplayCaptureResult> captureReplayResult(UUID targetUuid, String targetName) {
        String server = platform.getPlayerServer(targetUuid);
        if (!roster.isEnforcedOn(server)) return CompletableFuture.completedFuture(ReplayCaptureResult.noServerConnected());
        PendingCapture created = new PendingCapture(UUID.randomUUID().toString(), targetUuid);
        PendingCapture existing = byTarget.putIfAbsent(targetUuid, created);
        if (existing != null) return existing.result;
        byRequestId.put(created.requestId, created);
        ReplayCaptureRequest request = ReplayCaptureRequest.newBuilder()
                .setRequestId(created.requestId)
                .setTargetUuid(targetUuid.toString())
                .setTargetName(targetName)
                .setExpectedServer(server)
                .build();
        if (!sender.test(RealtimeEnvelope.newBuilder().setReplayCaptureRequest(request).build())) {
            finish(created.requestId, ReplayCaptureResult.noServerConnected());
            return created.result;
        }
        created.expireWith(scheduler.schedule(() -> finish(created.requestId, ReplayCaptureResult.error()),
                CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        return created.result;
    }

    @Override
    public ReplayCaptureStatus getReplayStatus(UUID playerUuid) {
        return roster.isEnforcedOn(platform.getPlayerServer(playerUuid))
                ? ReplayCaptureStatus.OK
                : ReplayCaptureStatus.NO_SERVER_CONNECTED;
    }

    void onResult(gg.modl.proto.modl.v1.ReplayCaptureResult result) {
        finish(result.getRequestId(), resultOf(result));
    }

    void disconnected() {
        List<String> pending = new ArrayList<>(byRequestId.keySet());
        for (String requestId : pending) finish(requestId, ReplayCaptureResult.noServerConnected());
    }

    static ReplayCaptureResult resultOf(gg.modl.proto.modl.v1.ReplayCaptureResult result) {
        switch (result.getOutcome()) {
            case REPLAY_CAPTURE_OUTCOME_OK:
                return ReplayCaptureResult.ok(result.hasReplayId() ? result.getReplayId() : null);
            case REPLAY_CAPTURE_OUTCOME_NO_ACTIVE_RECORDING:
                return ReplayCaptureResult.noActiveRecording();
            case REPLAY_CAPTURE_OUTCOME_NOT_HOSTED:
                return ReplayCaptureResult.notLocal();
            case REPLAY_CAPTURE_OUTCOME_NO_MEMBER:
            case REPLAY_CAPTURE_OUTCOME_MEMBER_DISCONNECTED:
                return ReplayCaptureResult.noServerConnected();
            default:
                return ReplayCaptureResult.error();
        }
    }

    private void finish(String requestId, ReplayCaptureResult result) {
        PendingCapture pending = byRequestId.remove(requestId);
        if (pending == null) return;
        byTarget.remove(pending.target, pending);
        pending.complete(result);
    }

    private static final class PendingCapture {
        private final String requestId;
        private final UUID target;
        private final CompletableFuture<ReplayCaptureResult> result = new CompletableFuture<>();
        private @Nullable ScheduledFuture<?> timeout;

        private PendingCapture(String requestId, UUID target) {
            this.requestId = requestId;
            this.target = target;
        }

        private synchronized void expireWith(ScheduledFuture<?> scheduled) {
            timeout = scheduled;
            if (result.isDone()) scheduled.cancel(false);
        }

        private synchronized void complete(ReplayCaptureResult outcome) {
            result.complete(outcome);
            ScheduledFuture<?> scheduled = timeout;
            if (scheduled != null) scheduled.cancel(false);
        }
    }
}
