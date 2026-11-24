package gg.modl.backend.player.repository;

import org.jspecify.annotations.Nullable;
import java.time.Instant;

public record LoginUpdate(
    String minecraftUuid,
    String username,
    @Nullable String ip,
    @Nullable IpGeo geo,
    @Nullable String serverName,
    @Nullable String skinHash,
    Instant loggedInAt
) {
}
