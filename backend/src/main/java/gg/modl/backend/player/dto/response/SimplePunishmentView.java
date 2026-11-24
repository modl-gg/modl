package gg.modl.backend.player.dto.response;

import org.jspecify.annotations.Nullable;
import java.util.List;

public record SimplePunishmentView(
    String id,
    String type,
    String category,
    int typeOrdinal,
    int ordinal,
    boolean started,
    @Nullable Long expiration,
    String description,
    String issuerName,
    long issuedAt,
    @Nullable String playerDescription,
    List<Modification> modifications,
    boolean silent
) {
    public record Modification(
        String type,
        @Nullable Long timestamp,
        long effectiveDuration,
        String issuerName
    ) {
    }
}
