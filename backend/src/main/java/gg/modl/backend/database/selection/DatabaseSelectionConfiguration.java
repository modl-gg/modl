package gg.modl.backend.database.selection;

import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.database.DatabaseDialect;
import gg.modl.backend.database.sql.PendingTargetCheck;
import gg.modl.backend.database.sql.SqlDatabaseProperties;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class DatabaseSelectionConfiguration {
    public static final String PROPERTIES_PREFIX = "modl.database";
    public static final String REOPEN_PROPERTY = PROPERTIES_PREFIX + ".reopen";

    @Bean
    DatabaseSelection databaseSelection(Environment environment, DataDirectoryProperties dataDirectory, OperatorNotice notice) {
        return DatabaseSelection.resolve(configured(Binder.get(environment)), dataDirectory,
            (target, moveToken) -> PendingTargetCheck.check(target, dataDirectory, moveToken), DatabaseSelection.REACH_BUDGET, notice);
    }

    @Bean
    SqlDatabaseProperties sqlDatabaseProperties(DatabaseSelection selection) {
        return selection.effective();
    }

    @Bean
    DepartureGuard departureGuard(DatabaseSelection selection, DatabaseDialect dialect, Environment environment,
                                  OperatorNotice notice) {
        String reopenedMove = Binder.get(environment).bind(REOPEN_PROPERTY, String.class).map(String::trim).orElse("");
        return new DepartureGuard(selection, dialect, reopenedMove.isEmpty() ? null : reopenedMove, notice);
    }

    public static SqlDatabaseProperties configured(Binder binder) {
        return binder.bind(PROPERTIES_PREFIX, SqlDatabaseProperties.class).orElseGet(SqlDatabaseProperties::builtIn);
    }
}
