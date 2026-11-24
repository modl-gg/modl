package gg.modl.backend.player.service;

public final class Shortenings {
    public static final PunishmentShortening PERMITTED = PunishmentShortening.grantedBy(() -> permission -> true);
    public static final PunishmentShortening REFUSED = PunishmentShortening.grantedBy(() -> permission -> false);

    private Shortenings() {
    }
}
