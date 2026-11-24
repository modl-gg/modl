package gg.modl.backend.player.data.punishment;

import org.jspecify.annotations.Nullable;
import java.time.Instant;

public record PunishmentEvidence(
    @Nullable String text,
    @Nullable String url,
    String type,
    @Nullable String uploadedBy,
    @Nullable String uploadedById,
    Instant uploadedAt,
    @Nullable String fileName,
    @Nullable String fileType,
    @Nullable Long fileSize
) {
}
