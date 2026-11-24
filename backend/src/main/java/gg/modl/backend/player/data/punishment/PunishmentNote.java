package gg.modl.backend.player.data.punishment;

import org.jspecify.annotations.Nullable;
import java.time.Instant;

public record PunishmentNote(
    String id,
    String text,
    Instant date,
    @Nullable String issuerName,
    @Nullable String issuerId
) {
}
