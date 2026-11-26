package gg.modl.backend.player.data.punishment;

import java.time.Instant;
import java.util.List;

public enum PunishmentPhase {
    ACTIVE,
    UNSTARTED,
    ENDED;

    public static final List<PunishmentPhase> NOT_ENDED = List.of(ACTIVE, UNSTARTED);

    public static PunishmentPhase of(Punishment punishment, Instant now) {
        if (punishment.getTypeOrdinal() == Punishment.KICK_TYPE_ORDINAL) {
            return ENDED;
        }
        if (!PunishmentState.activeUntil(punishment).isBefore(now)) {
            return punishment.getStarted() == null ? UNSTARTED : ACTIVE;
        }
        return punishment.isUnstarted() ? UNSTARTED : ENDED;
    }
}
