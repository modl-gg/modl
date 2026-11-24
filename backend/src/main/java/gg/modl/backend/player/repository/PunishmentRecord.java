package gg.modl.backend.player.repository;

import gg.modl.backend.player.data.punishment.Punishment;

public record PunishmentRecord(
    String playerId,
    String playerMinecraftUuid,
    String playerUsername,
    Punishment punishment,
    int version
) {
}
