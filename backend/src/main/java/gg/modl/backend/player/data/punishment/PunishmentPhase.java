package gg.modl.backend.player.data.punishment;

import java.time.Instant;

public enum PunishmentPhase {
    ACTIVE,
    UNSTARTED,
    ENDED;

    public static PunishmentPhase of(Punishment punishment, Instant now) {
        if (!PunishmentState.activeUntil(punishment).isBefore(now)) {
            return punishment.getStarted() == null ? UNSTARTED : ACTIVE;
        }
        return punishment.isUnstarted() ? UNSTARTED : ENDED;
    }
}
