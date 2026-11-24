package gg.modl.backend.databasemove.service;

import gg.modl.backend.database.sql.DurabilityWarning;

public sealed interface TargetWarning {
    record Durability(DurabilityWarning warning) implements TargetWarning {
    }

    record NotEncrypted(String host) implements TargetWarning {
    }

    record NoNightlyBackups() implements TargetWarning {
    }
}
