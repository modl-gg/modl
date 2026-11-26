package gg.modl.minecraft.core.network.link;

import gg.modl.minecraft.core.network.ReplayCaptureHandler;
import gg.modl.minecraft.core.service.ReplayCaptureResult;
import gg.modl.minecraft.core.service.ReplayRecordingResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

final class FakeReplayRequests implements ReplayCaptureHandler {
    private final List<String> calls = new ArrayList<>();
    private final ReplayRecordingResult answer;

    FakeReplayRequests(ReplayRecordingResult answer) {
        this.answer = answer;
    }

    List<String> calls() {
        return calls;
    }

    @Override
    public CompletableFuture<ReplayCaptureResult> capture(UUID target, String targetName) {
        calls.add("capture " + targetName);
        return CompletableFuture.completedFuture(ReplayCaptureResult.ok("replay-1"));
    }

    @Override
    public CompletableFuture<ReplayRecordingResult> startRecording(UUID target, String targetName) {
        calls.add("start " + targetName);
        return CompletableFuture.completedFuture(answer);
    }

    @Override
    public CompletableFuture<ReplayRecordingResult> stopRecording(UUID target, String targetName) {
        calls.add("stop " + targetName);
        return CompletableFuture.completedFuture(answer);
    }

    @Override
    public CompletableFuture<ReplayRecordingResult> recordingStatus(UUID target, String targetName) {
        calls.add("status " + targetName);
        return CompletableFuture.completedFuture(answer);
    }
}
