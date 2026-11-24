package gg.modl.backend.player.repository;

import gg.modl.backend.player.data.punishment.PunishmentPhase;
import org.jspecify.annotations.Nullable;

public record PunishmentListQuery(
    @Nullable PunishmentPhase phase,
    SortKey sortKey,
    boolean ascending,
    @Nullable IssuerMatch issuer,
    @Nullable Integer typeOrdinal,
    Evidence evidence
) {
    public enum SortKey {
        ISSUED, STARTED, DURATION
    }

    public enum Evidence {
        ANY, WITH, WITHOUT
    }
}
