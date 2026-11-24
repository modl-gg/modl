package gg.modl.backend.database.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import gg.modl.backend.database.DatabaseDialect;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.jooq.Configuration;
import org.jooq.DSLContext;
import org.jooq.ExecuteListener;
import org.jooq.ExecuteListenerProvider;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jooq.autoconfigure.JooqAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class JooqConfigurationTest {
    @ParameterizedTest
    @EnumSource(DatabaseDialect.class)
    void theDslContextUsesTheDialectAndTheKernelSettings(DatabaseDialect dialect) {
        runner(dialect).run(context -> {
            DSLContext dsl = context.getBean(DSLContext.class);

            assertEquals(dialect.sqlDialect(), dsl.dialect());
            assertFalse(dsl.settings().isExecuteLogging());
            assertFalse(dsl.settings().isRenderSchema());
        });
    }

    @ParameterizedTest
    @EnumSource(DatabaseDialect.class)
    void theDslContextHasExactlyTheKernelListeners(DatabaseDialect dialect) {
        runner(dialect).run(context -> assertEquals(
            List.of(WriteUnitGuard.class, StorableTextGuard.class, StatementDeadline.class, SqlExceptionTranslation.class),
            listenerTypes(context.getBean(DSLContext.class))));
    }

    @ParameterizedTest
    @EnumSource(DatabaseDialect.class)
    void bootsJooqAutoConfigurationBacksOffForTheKernelConfiguration(DatabaseDialect dialect) {
        runner(dialect).run(context -> {
            assertEquals(1, context.getBeansOfType(DSLContext.class).size());
            assertEquals(1, context.getBeansOfType(Configuration.class).size());
            assertSame(context.getBean(Configuration.class), context.getBean(DSLContext.class).configuration());
        });
    }

    private static ApplicationContextRunner runner(DatabaseDialect dialect) {
        return new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JooqAutoConfiguration.class))
            .withUserConfiguration(JooqConfiguration.class)
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withBean(DatabaseDialect.class, () -> dialect)
            .withBean(RunningStatements.class, RunningStatements::new)
            .withBean(OperatorNotice.class, OperatorNotice::new);
    }

    private static List<Class<? extends ExecuteListener>> listenerTypes(DSLContext dsl) {
        return Arrays.stream(dsl.configuration().executeListenerProviders())
            .map(ExecuteListenerProvider::provide)
            .<Class<? extends ExecuteListener>>map(ExecuteListener::getClass)
            .toList();
    }
}
