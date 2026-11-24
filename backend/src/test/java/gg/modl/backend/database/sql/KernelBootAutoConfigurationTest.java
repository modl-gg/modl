package gg.modl.backend.database.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.database.ExpiringRowsConfiguration;
import gg.modl.backend.database.WriteAttemptHook;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.infrastructure.scheduling.InstanceReadiness;
import gg.modl.backend.infrastructure.scheduling.ScheduledDatabaseJobs;
import gg.modl.backend.support.sql.SqlTestDatabase;
import gg.modl.backend.support.sql.TestDialects;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.ExecuteListenerProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.boot.health.autoconfigure.contributor.HealthContributorAutoConfiguration;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.health.DataSourceHealthContributorAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.metrics.DataSourcePoolMetricsAutoConfiguration;
import org.springframework.boot.jdbc.health.DataSourceHealthIndicator;
import org.springframework.boot.jooq.autoconfigure.JooqAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.PlatformTransactionManager;

class KernelBootAutoConfigurationTest {
    private static final List<String> POOLS = List.of("modl-sqlite-writer", "modl-sqlite-reader");

    @TempDir
    Path dataDirectory;

    @Test
    void bootsDataSourceTransactionAndJooqAutoConfigurationsBackOffForTheKernel() {
        runner().run(context -> {
            assertEquals(1, context.getBeansOfType(DataSource.class).size());
            assertInstanceOf(LazyConnectionDataSourceProxy.class, context.getBean(DataSource.class));
            assertEquals(1, context.getBeansOfType(PlatformTransactionManager.class).size());
            DataSourceTransactionManager transactions = assertInstanceOf(DataSourceTransactionManager.class,
                context.getBean(PlatformTransactionManager.class));
            assertSame(context.getBean(DataSource.class), transactions.getDataSource());
            assertEquals(1, context.getBeansOfType(DSLContext.class).size());
            assertEquals(List.of(WriteUnitGuard.class, StorableTextGuard.class, StatementDeadline.class, SqlExceptionTranslation.class),
                listenerTypes(context.getBean(DSLContext.class)));
        });
    }

    @Test
    void bootsFlywayAutoConfigurationNeverMigratesBesideTheKernel() {
        runner().run(context -> {
            assertEquals(1, context.getBeansOfType(Flyway.class).size());
            assertTrue(context.getBeansOfType(FlywayMigrationInitializer.class).isEmpty());
        });
    }

    @Test
    void theRoutingDataSourceDoesNotUnwrapToItsPools() {
        runner().run(context -> {
            DataSource dataSource = context.getBean(DataSource.class);

            assertEquals(false, dataSource.isWrapperFor(AbstractRoutingDataSource.class));
            assertThrows(SQLException.class, () -> dataSource.unwrap(AbstractRoutingDataSource.class));
        });
    }

    @Test
    void bothPoolsPublishMetrics() {
        runner().run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);

            for (String pool : POOLS) {
                assertNotNull(registry.find("hikaricp.connections").tag("pool", pool).gauge(), pool);
            }
        });
    }

    @Test
    void theDatabaseHealthGoesThroughTheFenceAndReportsDownOnceFenced() {
        runner().run(context -> {
            DataSourceHealthIndicator health = singleHealthIndicator(context);
            assertEquals(Status.UP, health.health().getStatus());

            context.getBean(InstanceFence.class).fence();

            assertEquals(Status.DOWN, health.health().getStatus());
        });
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
            .withPropertyValues("spring.flyway.enabled=false")
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                DataSourceTransactionManagerAutoConfiguration.class, TransactionAutoConfiguration.class,
                JdbcTemplateAutoConfiguration.class, FlywayAutoConfiguration.class, JooqAutoConfiguration.class,
                MetricsAutoConfiguration.class, CompositeMeterRegistryAutoConfiguration.class,
                SimpleMetricsExportAutoConfiguration.class, DataSourcePoolMetricsAutoConfiguration.class,
                HealthContributorAutoConfiguration.class, DataSourceHealthContributorAutoConfiguration.class))
            .withBean(SqlDatabaseProperties.class, () -> TestDialects.freshDatabase(TestDialects.SQLITE))
            .withBean(DataDirectoryProperties.class, () -> new DataDirectoryProperties(dataDirectory.toString()))
            .withBean(OperatorNotice.class, OperatorNotice::new)
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(WriteAttemptHook.class, () -> WriteAttemptHook.NONE)
            .withBean(InstanceReadiness.class, () -> SqlTestDatabase.COMMITTED)
            .withUserConfiguration(DataSourceConfiguration.class, FlywayConfiguration.class, JooqConfiguration.class,
                UnitOfWorkConfiguration.class, ExpiringRowsConfiguration.class, ScheduledDatabaseJobs.class);
    }

    private static DataSourceHealthIndicator singleHealthIndicator(AssertableApplicationContext context) {
        List<DataSourceHealthIndicator> indicators = List.copyOf(context.getBeansOfType(DataSourceHealthIndicator.class).values());
        assertEquals(1, indicators.size());
        return indicators.getFirst();
    }

    private static List<Class<?>> listenerTypes(DSLContext dsl) {
        return Arrays.stream(dsl.configuration().executeListenerProviders())
            .map(ExecuteListenerProvider::provide)
            .<Class<?>>map(Object::getClass)
            .toList();
    }
}
