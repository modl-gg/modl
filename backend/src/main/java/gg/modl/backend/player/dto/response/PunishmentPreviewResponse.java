package gg.modl.backend.player.dto.response;

import lombok.Builder;
import lombok.Data;
import org.jspecify.annotations.Nullable;

@Data
@Builder
public class PunishmentPreviewResponse implements PunishmentPreviewView {
    private final int status;
    private final boolean success;
    private final @Nullable String message;

    private final @Nullable String socialStatus;
    private final @Nullable String gameplayStatus;
    private final int socialPoints;
    private final int gameplayPoints;

    private final @Nullable String offenderStatus;

    private final @Nullable SeverityPreview lenient;
    private final @Nullable SeverityPreview regular;
    private final @Nullable SeverityPreview aggravated;

    private final @Nullable SeverityPreview singleSeverity;

    private final boolean singleSeverityPunishment;
    private final boolean permanentUntilUsernameChange;
    private final boolean permanentUntilSkinChange;
    private final boolean canBeAltBlocking;
    private final boolean canBeStatWiping;
    private final @Nullable String category;

    public static PunishmentPreviewResponse error(String message) {
        return PunishmentPreviewResponse.builder()
            .status(400)
            .success(false)
            .message(message)
            .build();
    }

    @Data
    @Builder
    public static class SeverityPreview implements PunishmentSeverityPreviewView {
        private final String severity;
        private final int points;
        private final long durationMs;
        private final String durationFormatted;
        private final String punishmentType;
        private final boolean permanent;

        private final String newSocialStatus;
        private final String newGameplayStatus;
        private final int newSocialPoints;
        private final int newGameplayPoints;
    }
}
