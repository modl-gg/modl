package gg.modl.backend.database.sql;

import gg.modl.backend.database.ChildRows;
import gg.modl.backend.database.ConstraintViolations;
import gg.modl.backend.database.DatabaseDialect;
import gg.modl.backend.database.ExpiredRowSweeper;
import gg.modl.backend.database.ExpiringRows;
import gg.modl.backend.database.InsertIfAbsent;
import gg.modl.backend.database.JsonColumns;
import gg.modl.backend.database.ReadLanes;
import gg.modl.backend.database.Sequencer;
import gg.modl.backend.database.TransactionalUnitOfWork;
import gg.modl.backend.database.Upserts;
import gg.modl.backend.database.WriteAttemptHook;
import gg.modl.backend.database.WritePause;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.infrastructure.scheduling.ScheduledDatabaseJobs;
import java.time.Clock;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
public class UnitOfWorkConfiguration {
    @Bean
    ReadLanes readLanes(DatabaseDialect dialect) {
        return ReadLanes.of(dialect);
    }

    @Bean(destroyMethod = "close")
    DatabaseWorkExecutor databaseWorkExecutor(ReadLanes lanes) {
        return new DatabaseWorkExecutor(lanes.permits(), DatabaseWorkExecutor.JOB_THREADS);
    }

    @Bean
    WritePause writePause() {
        return new WritePause();
    }

    @Bean
    TransactionalUnitOfWork unitOfWork(PlatformTransactionManager transactionManager, ConstraintViolations violations,
                                       ReadLanes lanes, DatabaseWorkExecutor work,
                                       ObjectProvider<WriteAttemptHook> attemptHook, WritePause writePause) {
        return new TransactionalUnitOfWork(transactionManager, violations, lanes, work,
            attemptHook.getIfAvailable(() -> WriteAttemptHook.NONE), writePause);
    }

    @Bean
    DatabaseLifecycle databaseLifecycle(InstanceFence fence, TransactionalUnitOfWork unitOfWork, DatabaseWorkExecutor work,
                                        RunningStatements statements, ConnectionPools pools) {
        return new DatabaseLifecycle(fence, unitOfWork, work, statements, pools, DatabaseLifecycle.DRAIN_BUDGET);
    }

    @Bean
    Sequencer sequencer(DSLContext dsl, Clock clock) {
        return Sequencer.seed(dsl, clock);
    }

    @Bean
    @DependsOn("sequencer")
    DurabilityAdvisor durabilityAdvisor(DatabaseDialect dialect, DSLContext dsl, OperatorNotice notice) {
        return new DurabilityAdvisor(dialect, dsl, notice);
    }

    @Bean
    InsertIfAbsent insertIfAbsent(DSLContext dsl, DatabaseDialect dialect) {
        return new InsertIfAbsent(dsl, dialect);
    }

    @Bean
    Upserts upserts(DSLContext dsl, DatabaseDialect dialect) {
        return new Upserts(dsl, dialect);
    }

    @Bean
    ChildRows childRows(DSLContext dsl, DatabaseDialect dialect, InsertIfAbsent insertIfAbsent) {
        return new ChildRows(dsl, dialect, insertIfAbsent);
    }

    @Bean
    JsonColumns jsonColumns() {
        return new JsonColumns();
    }

    @Bean
    ExpiredRowSweeper expiredRowSweeper(List<ExpiringRows> expiringRows, TransactionalUnitOfWork unitOfWork, DSLContext dsl,
                                        Clock clock) {
        return new ExpiredRowSweeper(expiringRows, unitOfWork, dsl, clock);
    }

    @Bean
    DatabaseSchedules databaseSchedules(ScheduledDatabaseJobs jobs, ExpiredRowSweeper sweeper, WalCheckpoints walCheckpoints) {
        return new DatabaseSchedules(jobs, sweeper, walCheckpoints);
    }
}
