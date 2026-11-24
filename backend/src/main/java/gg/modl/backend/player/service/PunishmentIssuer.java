package gg.modl.backend.player.service;

import gg.modl.backend.player.data.punishment.PunishmentIssuerKind;
import org.jspecify.annotations.Nullable;

public record PunishmentIssuer(String name, @Nullable String id, PunishmentIssuerKind kind) {
    public static PunishmentIssuer staff(String name, @Nullable String id) {
        return new PunishmentIssuer(name, id, PunishmentIssuerKind.STAFF);
    }

    public static PunishmentIssuer automated(String name) {
        return new PunishmentIssuer(name, null, PunishmentIssuerKind.AUTOMATED);
    }
}
