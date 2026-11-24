package gg.modl.backend.database.sql;

import gg.modl.backend.database.ConstraintViolations;
import gg.modl.backend.database.DatabaseDialect;
import gg.modl.backend.database.DiskFullNotice;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.ExecuteListenerProvider;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.jooq.impl.DataSourceConnectionProvider;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

@Configuration(proxyBeanMethods = false)
public class JooqConfiguration {
    @Bean
    DiskFullNotice diskFullNotice(OperatorNotice notice) {
        return new DiskFullNotice(notice);
    }

    @Bean
    ConstraintViolations constraintViolations(DatabaseDialect dialect, DiskFullNotice diskFullNotice) {
        return new ConstraintViolations(dialect, diskFullNotice);
    }

    @Bean(destroyMethod = "close")
    StatementDeadline statementDeadline(RunningStatements runningStatements) {
        return new StatementDeadline(runningStatements);
    }

    @Bean
    DefaultConfiguration jooqRuntimeConfiguration(DataSource dataSource, DatabaseDialect dialect, StatementDeadline statementDeadline,
                                           ConstraintViolations violations) {
        return configuration(dataSource, dialect, statementDeadline, violations);
    }

    @Bean
    DSLContext dslContext(DefaultConfiguration jooqRuntimeConfiguration) {
        return DSL.using(jooqRuntimeConfiguration);
    }

    public static DefaultConfiguration configuration(DataSource dataSource, DatabaseDialect dialect,
                                                     StatementDeadline statementDeadline, ConstraintViolations violations) {
        DefaultConfiguration configuration = new DefaultConfiguration();
        configuration.set(dialect.sqlDialect());
        configuration.set(new DataSourceConnectionProvider(new TransactionAwareDataSourceProxy(dataSource)));
        configuration.set(settings());
        configuration.set(listeners(statementDeadline, violations));
        return configuration;
    }

    static Settings settings() {
        return new Settings()
            .withRenderSchema(false)
            .withExecuteLogging(false);
    }

    private static ExecuteListenerProvider[] listeners(StatementDeadline statementDeadline, ConstraintViolations violations) {
        return DefaultExecuteListenerProvider.providers(new WriteUnitGuard(), new StorableTextGuard(), statementDeadline, new SqlExceptionTranslation(violations));
    }
}
