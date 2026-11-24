package gg.modl.backend.databasemove.service;

import gg.modl.backend.database.selection.DatabaseLocation;
import gg.modl.backend.update.install.InstanceRestart.Mode;
import java.util.Optional;

public record DatabaseSettingsView(
    DatabaseLocation current,
    Optional<String> username,
    boolean tls,
    boolean managed,
    Mode restartMode,
    Optional<DatabaseMove> move
) {
}
