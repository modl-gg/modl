package gg.modl.minecraft.core.service;

import org.jspecify.annotations.Nullable;

public final class ReplayCaptureResult {
    private static final ReplayCaptureResult NO_SERVER_CONNECTED =
            new ReplayCaptureResult(ReplayCaptureStatus.NO_SERVER_CONNECTED, null);
    private static final ReplayCaptureResult NO_ACTIVE_RECORDING =
            new ReplayCaptureResult(ReplayCaptureStatus.NO_ACTIVE_RECORDING, null);
    private static final ReplayCaptureResult NOT_LOCAL =
            new ReplayCaptureResult(ReplayCaptureStatus.NOT_LOCAL, null);
    private static final ReplayCaptureResult ERROR =
            new ReplayCaptureResult(ReplayCaptureStatus.ERROR, null);

    private final ReplayCaptureStatus status;
    private final @Nullable String replayId;

    private ReplayCaptureResult(ReplayCaptureStatus status, @Nullable String replayId) {
        this.status = status;
        this.replayId = replayId;
    }

    public static ReplayCaptureResult ok(@Nullable String replayId) {
        if (replayId == null || replayId.isEmpty()) {
            return error();
        }
        return new ReplayCaptureResult(ReplayCaptureStatus.OK, replayId);
    }

    public static ReplayCaptureResult noServerConnected() {
        return NO_SERVER_CONNECTED;
    }

    public static ReplayCaptureResult noActiveRecording() {
        return NO_ACTIVE_RECORDING;
    }

    public static ReplayCaptureResult notLocal() {
        return NOT_LOCAL;
    }

    public static ReplayCaptureResult error() {
        return ERROR;
    }

    public static ReplayCaptureResult of(ReplayCaptureStatus status, @Nullable String replayId) {
        if (status == ReplayCaptureStatus.OK) {
            return ok(replayId);
        }
        if (status == ReplayCaptureStatus.NO_SERVER_CONNECTED) return noServerConnected();
        if (status == ReplayCaptureStatus.NOT_LOCAL) return notLocal();
        if (status == ReplayCaptureStatus.ERROR) return error();
        return noActiveRecording();
    }

    public ReplayCaptureStatus getStatus() {
        return status;
    }

    public @Nullable String getReplayId() {
        return replayId;
    }
}
