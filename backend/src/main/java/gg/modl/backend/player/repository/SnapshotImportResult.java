package gg.modl.backend.player.repository;

import java.util.Map;

public record SnapshotImportResult(Map<String, String> storedIdByUuid, int created, int merged, Map<String, String> renamedPunishmentIds) {
    public SnapshotImportResult {
        storedIdByUuid = Map.copyOf(storedIdByUuid);
        renamedPunishmentIds = Map.copyOf(renamedPunishmentIds);
    }
}
