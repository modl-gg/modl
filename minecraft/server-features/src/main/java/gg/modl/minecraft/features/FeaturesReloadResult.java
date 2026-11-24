package gg.modl.minecraft.features;

import java.util.Collections;
import java.util.List;

public final class FeaturesReloadResult {
    private final List<String> failures;
    private final List<String> restartRequired;

    FeaturesReloadResult(List<String> failures, List<String> restartRequired) {
        this.failures = Collections.unmodifiableList(failures);
        this.restartRequired = Collections.unmodifiableList(restartRequired);
    }

    public boolean isSuccessful() {
        return failures.isEmpty();
    }

    public List<String> getFailures() {
        return failures;
    }

    public List<String> getRestartRequired() {
        return restartRequired;
    }

    public boolean needsRestart() {
        return !restartRequired.isEmpty();
    }

    public String describeFailures() {
        return String.join(", ", failures);
    }

    public String describeRestartRequired() {
        return String.join(", ", restartRequired);
    }
}
