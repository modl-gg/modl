package gg.modl.backend.database.sql;

import gg.modl.backend.database.DatabaseDialect;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class FlywayConfiguration {
    @Bean
    Flyway flyway(DatabaseDialect dialect, DatabaseConnections connections) {
        return DatabaseMigration.configure(dialect, connections.migration(), FlywayConfiguration.class.getClassLoader()).load();
    }

    @Bean
    DatabaseMigration databaseMigration(DatabaseDialect dialect, Flyway flyway, ObjectProvider<MigrationGuard> guards) {
        List<MigrationGuard> ordered = guards.orderedStream().toList();
        ordered.forEach(guard -> guard.beforeMigration(flyway));
        DatabaseMigration migration = DatabaseMigration.migrate(dialect, flyway);
        ordered.forEach(guard -> guard.afterMigration(migration));
        return migration;
    }
}
