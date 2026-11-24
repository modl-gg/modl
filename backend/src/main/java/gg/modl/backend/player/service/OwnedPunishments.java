package gg.modl.backend.player.service;

import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.util.UuidUtils;
import gg.modl.backend.player.repository.PunishmentRecord;
import gg.modl.backend.player.repository.PunishmentRepository;
import java.util.Optional;
import java.util.UUID;

final class OwnedPunishments {
    private OwnedPunishments() {
    }

    static Optional<PunishmentRecord> find(PunishmentRepository repository, String playerUuid, String punishmentId) {
        String owner = UuidUtils.normalize(playerUuid);
        return repository.findById(punishmentId).filter(found -> found.playerMinecraftUuid().equals(owner));
    }

    static PunishmentRecord require(PunishmentRepository repository, UUID playerUuid, String punishmentId) {
        return find(repository, playerUuid.toString(), punishmentId)
            .orElseThrow(() -> new ResourceNotFoundException("Punishment not found"));
    }
}
