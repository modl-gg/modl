package gg.modl.backend.ai.data;

import java.time.Instant;
import lombok.Data;
import org.jspecify.annotations.Nullable;

@Data
public class AIAnalysisResult {
    private final String analysis;
    @Nullable
    private final SuggestedAction suggestedAction;
    private final Instant createdAt;
    private final String rawResponse;
    @Nullable
    private Double confidence;
    private boolean wasAppliedAutomatically;
    @Nullable
    private String appliedBy;
    private boolean dismissed = false;

    public boolean hasViolation() {
        return suggestedAction != null;
    }

    @Data
    public static class SuggestedAction {
        private final int punishmentTypeId;
        private final String severity;
    }
}
