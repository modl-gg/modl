package gg.modl.backend.database.backup;

import java.util.Set;

public record UpgradeLabel(String fromVersion, String toVersion, Set<String> createdTables) {
    public UpgradeLabel {
        createdTables = Set.copyOf(createdTables);
    }
}
