package gg.modl.minecraft.core.network.link;

import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.service.ReplayCaptureResult;
import gg.modl.minecraft.core.service.ReplayCaptureStatus;
import gg.modl.minecraft.core.service.ReplayRecordingResult;
import gg.modl.minecraft.core.service.ReplayService;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import gg.modl.proto.modl.v1.ReplayCaptureOutcome;
import gg.modl.proto.modl.v1.ReplayCaptureRequest;
import gg.modl.proto.modl.v1.ReplayRequestAction;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class NetworkReplayService implements ReplayService {
    static final long CAPTURE_TIMEOUT_SECONDS = 600;
    static final long RECORDING_TIMEOUT_SECONDS = 30;

    private final Platform platform;
    private final NetworkRoster roster;
    private final Predicate<RealtimeEnvelope> sender;
    private final ScheduledExecutorService scheduler;
    private final Map<String, PendingRequest> byRequestId = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<ReplayCaptureResult>> capturesByTarget = new ConcurrentHashMap<>();

    NetworkReplayService(Platform platform, NetworkRoster roster, Predicate<RealtimeEnvelope> sender,
                         ScheduledExecutorService scheduler) {
        this.platform = platform;
        this.roster = roster;
        this.sender = sender;
        this.scheduler = scheduler;
    }

    @Override
    public CompletableFuture<ReplayCaptureResult> captureReplayResult(UUID targetUuid, String targetName) {
        CompletableFuture<ReplayCaptureResult> created = new CompletableFuture<>();
        CompletableFuture<ReplayCaptureResult> existing = capturesByTarget.putIfAbsent(targetUuid, created);
        if (existing != null) return existing;
        request(targetUuid, targetName, ReplayRequestAction.REPLAY_REQUEST_ACTION_CAPTURE, CAPTURE_TIMEOUT_SECONDS, answer -> {
            capturesByTarget.remove(targetUuid, created);
            created.complete(resultOf(answer));
        });
        return created;
    }

    @Override
    public CompletableFuture<ReplayRecordingResult> startRecording(UUID targetUuid, String targetName) {
        return recording(targetUuid, targetName, ReplayRequestAction.REPLAY_REQUEST_ACTION_START_RECORDING);
    }

    @Override
    public CompletableFuture<ReplayRecordingResult> stopRecording(UUID targetUuid, String targetName) {
        return recording(targetUuid, targetName, ReplayRequestAction.REPLAY_REQUEST_ACTION_STOP_RECORDING);
    }

    @Override
    public CompletableFuture<ReplayRecordingResult> recordingStatus(UUID targetUuid, String targetName) {
        return recording(targetUuid, targetName, ReplayRequestAction.REPLAY_REQUEST_ACTION_RECORDING_STATUS);
    }

    @Override
    public ReplayCaptureStatus getReplayStatus(UUID playerUuid) {
        return roster.isEnforcedOn(platform.getPlayerServer(playerUuid))
                ? ReplayCaptureStatus.OK
                : ReplayCaptureStatus.NO_SERVER_CONNECTED;
    }

    void onResult(gg.modl.proto.modl.v1.ReplayCaptureResult result) {
        finish(result.getRequestId(), result);
    }

    void disconnected() {
        List<String> pending = new ArrayList<>(byRequestId.keySet());
        for (String requestId : pending) finish(requestId, answer(requestId, ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_MEMBER_DISCONNECTED));
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

    private CompletableFuture<ReplayRecordingResult> recording(UUID targetUuid, String targetName, ReplayRequestAction action) {
        CompletableFuture<ReplayRecordingResult> result = new CompletableFuture<>();
        request(targetUuid, targetName, action, RECORDING_TIMEOUT_SECONDS,
                answer -> result.complete(ReplayRecordingProtos.fromResult(answer)));
        return result;
    }

    private void request(UUID targetUuid, String targetName, ReplayRequestAction action, long timeoutSeconds,
                         Consumer<gg.modl.proto.modl.v1.ReplayCaptureResult> onAnswer) {
        String server = platform.getPlayerServer(targetUuid);
        PendingRequest created = new PendingRequest(UUID.randomUUID().toString(), onAnswer);
        if (!roster.isEnforcedOn(server)) {
            created.complete(answer(created.requestId, ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_NO_MEMBER));
            return;
        }
        byRequestId.put(created.requestId, created);
        try {
            created.expireWith(scheduler.schedule(
                    () -> finish(created.requestId, answer(created.requestId, ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_TIMED_OUT)),
                    timeoutSeconds, TimeUnit.SECONDS));
        } catch (RejectedExecutionException stopped) {
            finish(created.requestId, answer(created.requestId, ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_NO_MEMBER));
            return;
        }
        ReplayCaptureRequest request = ReplayCaptureRequest.newBuilder()
                .setRequestId(created.requestId)
                .setTargetUuid(targetUuid.toString())
                .setTargetName(targetName)
                .setExpectedServer(server)
                .setAction(action)
                .build();
        if (!sender.test(RealtimeEnvelope.newBuilder().setReplayCaptureRequest(request).build())) {
            finish(created.requestId, answer(created.requestId, ReplayCaptureOutcome.REPLAY_CAPTURE_OUTCOME_NO_MEMBER));
        }
    }

    private void finish(String requestId, gg.modl.proto.modl.v1.ReplayCaptureResult result) {
        PendingRequest pending = byRequestId.remove(requestId);
        if (pending != null) pending.complete(result);
    }

    private static gg.modl.proto.modl.v1.ReplayCaptureResult answer(String requestId, ReplayCaptureOutcome outcome) {
        return gg.modl.proto.modl.v1.ReplayCaptureResult.newBuilder().setRequestId(requestId).setOutcome(outcome).build();
    }

    private static final class PendingRequest {
        private final String requestId;
        private final Consumer<gg.modl.proto.modl.v1.ReplayCaptureResult> onAnswer;
        private @Nullable ScheduledFuture<?> timeout;
        private boolean answered;

        private PendingRequest(String requestId, Consumer<gg.modl.proto.modl.v1.ReplayCaptureResult> onAnswer) {
            this.requestId = requestId;
            this.onAnswer = onAnswer;
        }

        private synchronized void expireWith(ScheduledFuture<?> scheduled) {
            timeout = scheduled;
            if (answered) scheduled.cancel(false);
        }

        private void complete(gg.modl.proto.modl.v1.ReplayCaptureResult answer) {
            synchronized (this) {
                if (answered) return;
                answered = true;
                ScheduledFuture<?> scheduled = timeout;
                if (scheduled != null) scheduled.cancel(false);
            }
            onAnswer.accept(answer);
        }
    }
}
