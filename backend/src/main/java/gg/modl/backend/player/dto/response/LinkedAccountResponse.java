package gg.modl.backend.player.dto.response;

import org.jspecify.annotations.Nullable;
import java.time.Instant;

public record LinkedAccountResponse(
    String minecraftUuid,
    String username,
    int activeBans,
    int activeMutes,
    @Nullable Instant lastLinkedUpdate
) {
}
