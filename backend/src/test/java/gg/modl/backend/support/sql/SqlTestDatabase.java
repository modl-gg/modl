package gg.modl.backend.support.sql;

import gg.modl.backend.database.ChildRows;
import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.database.ExpiringRowsConfiguration;
import gg.modl.backend.database.InsertIfAbsent;
import gg.modl.backend.database.JsonColumns;
import gg.modl.backend.database.Sequencer;
import gg.modl.backend.database.TransactionalUnitOfWork;
import gg.modl.backend.database.Upserts;
import gg.modl.backend.database.WriteAttemptHook;
import gg.modl.backend.database.sql.ConnectionTracker;
import gg.modl.backend.database.sql.DataSourceConfiguration;
import gg.modl.backend.database.DatabaseDialect;
import gg.modl.backend.database.sql.FlywayConfiguration;
import gg.modl.backend.database.sql.InstanceFence;
import gg.modl.backend.database.sql.JooqConfiguration;
import gg.modl.backend.database.sql.SqlDatabaseProperties;
import gg.modl.backend.database.sql.UnitOfWorkConfiguration;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.infrastructure.scheduling.InstanceReadiness;
import gg.modl.backend.infrastructure.scheduling.ScheduledDatabaseJobs;
import gg.modl.backend.support.MutableClock;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.jooq.DSLContext;
import org.jooq.Table;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

public final class SqlTestDatabase implements AutoCloseable {
    public static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    public static final InstanceReadiness COMMITTED = () -> true;

    private final DialectTarget target;
    private final AnnotationConfigApplicationContext context;
    private final MutableClock clock;
    private final Runnable disposal;

    private SqlTestDatabase(DialectTarget target, AnnotationConfigApplicationContext context, MutableClock clock,
                            Runnable disposal) {
        this.target = target;
        this.context = context;
        this.clock = clock;
        this.disposal = disposal;
    }

    public static SqlTestDatabase start(DialectTarget target, Path dataDirectory) {
        return start(target, dataDirectory, WriteAttemptHook.NONE);
    }

    public static SqlTestDatabase start(DialectTarget target, Path dataDirectory, WriteAttemptHook attemptHook) {
        SqlDatabaseProperties ownDatabase = SchemaTemplate.migratedCopy(target);
        return start(target, dataDirectory, ownDatabase, attemptHook, () -> TestDialects.drop(target, ownDatabase));
    }

    public static SqlTestDatabase start(DialectTarget target, Path dataDirectory, SqlDatabaseProperties database,
                                        WriteAttemptHook attemptHook) {
        return start(target, dataDirectory, database, attemptHook, () -> { });
    }

    private static SqlTestDatabase start(DialectTarget target, Path dataDirectory, SqlDatabaseProperties database,
                                         WriteAttemptHook attemptHook, Runnable disposal) {
        MutableClock clock = new MutableClock(START);
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(SqlDatabaseProperties.class, () -> database);
        context.registerBean(DataDirectoryProperties.class, () -> new DataDirectoryProperties(dataDirectory.toString()));
        context.registerBean(OperatorNotice.class, OperatorNotice::new);
        context.registerBean(Clock.class, () -> clock);
        context.registerBean(WriteAttemptHook.class, () -> attemptHook);
        context.registerBean(InstanceReadiness.class, () -> COMMITTED);
        context.register(DataSourceConfiguration.class, FlywayConfiguration.class, JooqConfiguration.class,
            UnitOfWorkConfiguration.class, ExpiringRowsConfiguration.class, ScheduledDatabaseJobs.class);
        context.refresh();
        return new SqlTestDatabase(target, context, clock, disposal);
    }

    public DialectTarget target() {
        return target;
    }

    public DatabaseDialect dialect() {
        return target.dialect();
    }

    public MutableClock clock() {
        return clock;
    }

    public DSLContext dsl() {
        return bean(DSLContext.class);
    }

    public TransactionalUnitOfWork unitOfWork() {
        return bean(TransactionalUnitOfWork.class);
    }

    public InsertIfAbsent insertIfAbsent() {
        return bean(InsertIfAbsent.class);
    }

    public Upserts upserts() {
        return bean(Upserts.class);
    }

    public ChildRows childRows() {
        return bean(ChildRows.class);
    }

    public JsonColumns jsonColumns() {
        return bean(JsonColumns.class);
    }

    public Sequencer sequencer() {
        return bean(Sequencer.class);
    }

    public ConnectionTracker connectionTracker() {
        return bean(ConnectionTracker.class);
    }

    public InstanceFence fence() {
        return bean(InstanceFence.class);
    }

    public <T> T bean(Class<T> type) {
        return context.getBean(type);
    }

    public void reset() {
        unitOfWork().write(() -> {
            for (Table<?> table : SchemaOrder.childrenFirst()) {
                dsl().deleteFrom(table).execute();
            }
        });
        clock.advance(Duration.between(clock.instant(), START));
    }

    @Override
    public void close() {
        context.close();
        disposal.run();
    }
}
