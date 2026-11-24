package gg.modl.backend.player.dto.response;

import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.Map;

public record AppealInfoView(
    String id,
    String type,
    @Nullable Instant issued,
    @Nullable Instant expires,
    boolean active,
    boolean appealable,
    String playerUuid,
    @Nullable Map<String, Object> existingAppeal,
    @Nullable Map<String, Object> appealForm
) {
}
