package gg.modl.backend.database.sql;

import org.flywaydb.core.Flyway;

public interface MigrationGuard {
    default void beforeMigration(Flyway flyway) {
    }

    default void afterMigration(DatabaseMigration migration) {
    }
}
