package gg.modl.backend.player.repository;

import gg.modl.backend.player.data.Player;
import java.time.Instant;
import java.util.List;
import java.util.function.UnaryOperator;

public interface PlayerImportRepository {
    ImportBatchResult importBatch(List<Player> players, Instant now, ImportedAddressRetention retention);

    SnapshotImportResult importSnapshots(List<PlayerSnapshot> snapshots, UnaryOperator<String> collisionId,
                                         ImportedAddressRetention retention);
}
