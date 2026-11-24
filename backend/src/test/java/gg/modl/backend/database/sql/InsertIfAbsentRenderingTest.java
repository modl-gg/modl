package gg.modl.backend.database.sql;

import static gg.modl.backend.database.jooq.Indexes.UQ_PLAYERS_MINECRAFT_UUID;
import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static gg.modl.backend.database.jooq.Tables.SETTINGS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import gg.modl.backend.database.ConstraintViolations;
import gg.modl.backend.database.DatabaseDialect;
import gg.modl.backend.database.DiskFullNotice;
import gg.modl.backend.database.InsertIfAbsent;
import gg.modl.backend.database.ReportExecutor;
import gg.modl.backend.database.ReadLanes;
import gg.modl.backend.database.TransactionalUnitOfWork;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.database.Upserts;
import gg.modl.backend.database.WriteAttemptHook;
import gg.modl.backend.database.WritePause;
import gg.modl.backend.database.jooq.tables.records.PlayersRecord;
import gg.modl.backend.database.jooq.tables.records.SettingsRecord;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class InsertIfAbsentRenderingTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final List<String> executed = new ArrayList<>();
    private final StatementDeadline statementDeadline = new StatementDeadline(new RunningStatements());

    @AfterEach
    void closeDeadlineTimer() {
        statementDeadline.close();
    }

    @ParameterizedTest
    @EnumSource(value = DatabaseDialect.class, names = {"SQLITE", "POSTGRESQL"})
    void aNamedTargetRendersOnConflictDoNothingForThatTarget(DatabaseDialect dialect) {
        Kernel kernel = kernel(dialect);

        assertTrue(kernel.write(() -> kernel.insertIfAbsent().on(PLAYERS, UQ_PLAYERS_MINECRAFT_UUID).insert(player())));

        assertTrue(onlyStatement().contains("on conflict (minecraft_uuid) do nothing"), onlyStatement());
    }

    @ParameterizedTest
    @EnumSource(value = DatabaseDialect.class, names = {"SQLITE", "POSTGRESQL"})
    void aPrimaryKeyTargetRendersOnConflictDoNothingForThePrimaryKey(DatabaseDialect dialect) {
        Kernel kernel = kernel(dialect);

        assertTrue(kernel.write(() -> kernel.insertIfAbsent().onPrimaryKey(SETTINGS).insert(settings())));

        assertTrue(onlyStatement().contains("on conflict (type) do nothing"), onlyStatement());
    }

    @ParameterizedTest
    @EnumSource(value = DatabaseDialect.class, names = {"SQLITE", "POSTGRESQL"})
    void anyUniqueRendersOnConflictDoNothingWithoutTarget(DatabaseDialect dialect) {
        Kernel kernel = kernel(dialect);

        assertTrue(kernel.write(() -> kernel.insertIfAbsent().onAnyUnique(PLAYERS).insert(player())));

        assertTrue(onlyStatement().contains("on conflict do nothing"), onlyStatement());
    }

    @ParameterizedTest
    @EnumSource(value = DatabaseDialect.class, names = {"MYSQL", "MARIADB"})
    void aNamedTargetIsAPlainInsertOnTheMysqlFamily(DatabaseDialect dialect) {
        Kernel kernel = kernel(dialect);

        assertTrue(kernel.write(() -> kernel.insertIfAbsent().on(PLAYERS, UQ_PLAYERS_MINECRAFT_UUID).insert(player())));

        assertPlainInsert(onlyStatement());
    }

    @ParameterizedTest
    @EnumSource(value = DatabaseDialect.class, names = {"MYSQL", "MARIADB"})
    void aPrimaryKeyTargetIsAPlainInsertOnTheMysqlFamily(DatabaseDialect dialect) {
        Kernel kernel = kernel(dialect);

        assertTrue(kernel.write(() -> kernel.insertIfAbsent().onPrimaryKey(SETTINGS).insert(settings())));

        assertPlainInsert(onlyStatement());
    }

    @ParameterizedTest
    @EnumSource(value = DatabaseDialect.class, names = {"MYSQL", "MARIADB"})
    void anyUniqueIsAPlainInsertOnTheMysqlFamily(DatabaseDialect dialect) {
        Kernel kernel = kernel(dialect);

        assertTrue(kernel.write(() -> kernel.insertIfAbsent().onAnyUnique(PLAYERS).insert(player())));

        assertPlainInsert(onlyStatement());
    }

    @ParameterizedTest
    @EnumSource(value = DatabaseDialect.class, names = {"SQLITE", "POSTGRESQL"})
    void anUpsertRendersOnConflictDoUpdateOnThePrimaryKey(DatabaseDialect dialect) {
        Kernel kernel = kernel(dialect);

        kernel.write(() -> {
            kernel.upserts().onPrimaryKey(SETTINGS, settings(), Map.of(SETTINGS.DATA_JSON, "{}"));
            return null;
        });

        assertTrue(onlyStatement().matches(".* on conflict \\(type\\) do update set (modl_settings\\.)?data_json = \\?"), onlyStatement());
    }

    @ParameterizedTest
    @EnumSource(value = DatabaseDialect.class, names = {"MYSQL", "MARIADB"})
    void anUpsertRendersOnDuplicateKeyUpdateOnTheMysqlFamily(DatabaseDialect dialect) {
        Kernel kernel = kernel(dialect);

        kernel.write(() -> {
            kernel.upserts().onPrimaryKey(SETTINGS, settings(), Map.of(SETTINGS.DATA_JSON, "{}"));
            return null;
        });

        String statement = onlyStatement();
        assertTrue(statement.startsWith("insert into modl_settings"), statement);
        assertTrue(statement.matches(".* on duplicate key update (modl_settings\\.)?data_json = \\?"), statement);
        assertFalse(statement.contains("ignore"), statement);
    }

    private static void assertPlainInsert(String statement) {
        assertTrue(statement.startsWith("insert into "), statement);
        assertFalse(statement.contains("ignore"), statement);
        assertFalse(statement.contains("on duplicate key"), statement);
        assertFalse(statement.contains("on conflict"), statement);
    }

    private String onlyStatement() {
        assertEquals(1, executed.size(), executed.toString());
        return executed.getFirst().toLowerCase(Locale.ROOT).replace("\"", "").replace("`", "");
    }

    private Kernel kernel(DatabaseDialect dialect) {
        MockConnection connection = new MockConnection(context -> {
            executed.add(context.sql());
            return new MockResult[] {new MockResult(1)};
        });
        DataSource dataSource = new SingleConnectionDataSource(connection, true);
        ConstraintViolations violations = new ConstraintViolations(dialect, new DiskFullNotice(new OperatorNotice()));
        DSLContext dsl = DSL.using(JooqConfiguration.configuration(dataSource, dialect, statementDeadline, violations));
        UnitOfWork unitOfWork = new TransactionalUnitOfWork(new DataSourceTransactionManager(dataSource), violations,
            ReadLanes.of(dialect), mock(ReportExecutor.class), WriteAttemptHook.NONE, new WritePause());
        return new Kernel(unitOfWork, new InsertIfAbsent(dsl, dialect), new Upserts(dsl, dialect));
    }

    private static PlayersRecord player() {
        PlayersRecord player = new PlayersRecord();
        player.setId("p1");
        player.setMinecraftUuid("00000000-0000-0000-0000-000000000001");
        player.setUsername("Steve");
        player.setUsernameLower("steve");
        player.setCreatedAt(NOW);
        return player;
    }

    private static SettingsRecord settings() {
        return new SettingsRecord("general", "{}", 0L, NOW);
    }

    private record Kernel(UnitOfWork unitOfWork, InsertIfAbsent insertIfAbsent, Upserts upserts) {
        <T> T write(Supplier<T> unit) {
            return unitOfWork.write(unit);
        }
    }
}
