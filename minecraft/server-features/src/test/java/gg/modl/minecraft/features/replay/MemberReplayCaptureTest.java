package gg.modl.minecraft.features.replay;

import gg.modl.minecraft.core.service.ReplayCaptureResult;
import gg.modl.minecraft.core.service.ReplayRecordingOutcome;
import gg.modl.minecraft.core.service.ReplayRecordingResult;
import gg.modl.minecraft.core.service.ReplayService;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.features.FeaturePlayer;
import gg.modl.minecraft.features.FeaturePlayerProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MemberReplayCaptureTest {
    private static final PluginLogger LOGGER = PluginLogger.fromJul(Logger.getLogger("MemberReplayCaptureTest"));

    private final UUID target = UUID.randomUUID();
    private final Set<UUID> online = new HashSet<>();
    private final LocalReplays local = new LocalReplays();
    private ReplayService service = local;
    private final MemberReplayCapture capture = new MemberReplayCapture(new OnlinePlayers(), () -> service, LOGGER);

    @Test
    void aRecordingRequestForAPlayerHereReachesTheLocalRecorder() {
        online.add(target);

        assertEquals(ReplayRecordingOutcome.OK, capture.startRecording(target, "Steve").join().getOutcome());
        assertEquals(ReplayRecordingOutcome.OK, capture.stopRecording(target, "Steve").join().getOutcome());
        assertEquals(ReplayRecordingOutcome.OK, capture.recordingStatus(target, "Steve").join().getOutcome());

        assertEquals(Arrays.asList("start Steve", "stop Steve", "status Steve"), local.calls);
    }

    @Test
    void aPlayerWhoIsNotHereIsNotLocal() {
        assertEquals(ReplayRecordingOutcome.NOT_LOCAL, capture.startRecording(target, "Steve").join().getOutcome());
        assertEquals(Collections.emptyList(), local.calls);
    }

    @Test
    void withoutRecordingOnThisServerTheRequestIsNotAvailable() {
        online.add(target);
        service = null;

        assertEquals(ReplayRecordingOutcome.NOT_AVAILABLE, capture.startRecording(target, "Steve").join().getOutcome());
    }

    @Test
    void aFailedRecordingRequestAnswersAnError() {
        online.add(target);
        local.failure = new IllegalStateException("boom");

        assertEquals(ReplayRecordingOutcome.ERROR, capture.startRecording(target, "Steve").join().getOutcome());
    }

    private final class OnlinePlayers implements FeaturePlayerProvider {
        @Override
        public Collection<FeaturePlayer> getOnlinePlayers() {
            return Collections.emptyList();
        }

        @Override
        public boolean isOnline(UUID uuid) {
            return online.contains(uuid);
        }

        @Override
        public void dispatchConsoleCommand(String command) {
        }
    }

    private static final class LocalReplays implements ReplayService {
        private final List<String> calls = new ArrayList<>();
        private RuntimeException failure;

        @Override
        public CompletableFuture<ReplayCaptureResult> captureReplayResult(UUID targetUuid, String targetName) {
            calls.add("capture " + targetName);
            return CompletableFuture.completedFuture(ReplayCaptureResult.noActiveRecording());
        }

        @Override
        public CompletableFuture<ReplayRecordingResult> startRecording(UUID targetUuid, String targetName) {
            return answer("start " + targetName);
        }

        @Override
        public CompletableFuture<ReplayRecordingResult> stopRecording(UUID targetUuid, String targetName) {
            return answer("stop " + targetName);
        }

        @Override
        public CompletableFuture<ReplayRecordingResult> recordingStatus(UUID targetUuid, String targetName) {
            return answer("status " + targetName);
        }

        private CompletableFuture<ReplayRecordingResult> answer(String call) {
            calls.add(call);
            CompletableFuture<ReplayRecordingResult> result = new CompletableFuture<>();
            if (failure != null) result.completeExceptionally(failure);
            else result.complete(ReplayRecordingResult.of(ReplayRecordingOutcome.OK));
            return result;
        }
    }
}
