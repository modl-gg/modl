package gg.modl.backend.server.data;

import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;

public record SetupProgress(
    @Nullable Instant startedAt,
    @Nullable Instant ownerSignedInAt,
    @Nullable Instant completedAt,
    @Nullable Map<String, String> steps
) {
    public boolean ownerHasSignedIn() {
        return ownerSignedInAt != null;
    }

    public boolean isCompleted() {
        return completedAt != null;
    }

    public SetupStepStatus statusOf(SetupStep step) {
        if (step == SetupStep.ACCOUNT) {
            return ownerHasSignedIn() ? SetupStepStatus.DONE : SetupStepStatus.PENDING;
        }
        String stored = steps == null ? null : steps.get(step.name());
        return Arrays.stream(SetupStepStatus.values())
            .filter(status -> status.name().equals(stored))
            .findFirst()
            .orElse(SetupStepStatus.PENDING);
    }
}
