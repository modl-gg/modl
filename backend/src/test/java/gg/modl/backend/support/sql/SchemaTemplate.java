package gg.modl.backend.support.sql;

import gg.modl.backend.backup.FileTrees;
import gg.modl.backend.database.DatabaseDialect;
import gg.modl.backend.database.WriteAttemptHook;
import gg.modl.backend.database.sql.SqlDatabaseProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class SchemaTemplate {
    private static final Map<String, SchemaTemplate> TEMPLATES = new ConcurrentHashMap<>();
    private static final String TABLES = "SELECT table_name FROM information_schema.tables"
        + " WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' ORDER BY table_name";

    private final DialectTarget target;
    private final String name;
    private final List<String> copyStatements;

    private SchemaTemplate(DialectTarget target, String name, List<String> copyStatements) {
        this.target = target;
        this.name = name;
        this.copyStatements = copyStatements;
    }

    static SqlDatabaseProperties migratedCopy(DialectTarget target) {
        if (target.isSqlite()) {
            return TestDialects.freshDatabase(target);
        }
        return TEMPLATES.computeIfAbsent(target.name(), key -> migrate(target)).copy();
    }

    private static SchemaTemplate migrate(DialectTarget target) {
        SqlDatabaseProperties template = TestDialects.freshDatabase(target);
        migrateInto(target, template);
        List<String> copyStatements = target.dialect() == DatabaseDialect.POSTGRESQL ? List.of() : mysqlCopyStatements(target, template);
        return new SchemaTemplate(target, TestDialects.databaseName(template), copyStatements);
    }

    private static void migrateInto(DialectTarget target, SqlDatabaseProperties template) {
        try {
            Path dataDirectory = Files.createTempDirectory("modl-schema-template-");
            try {
                SqlTestDatabase.start(target, dataDirectory, template, WriteAttemptHook.NONE).close();
            } finally {
                FileTrees.deleteRecursively(dataDirectory);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not prepare the schema template for " + target, e);
        }
    }

    private static List<String> mysqlCopyStatements(DialectTarget target, SqlDatabaseProperties template) {
        String source = TestDialects.databaseName(template);
        List<String> statements = new ArrayList<>(List.of("SET FOREIGN_KEY_CHECKS = 0"));
        try (Connection connection = TestDialects.connect(target, template); Statement statement = connection.createStatement()) {
            for (String table : strings(statement, TABLES)) {
                statements.add(strings(statement, "SHOW CREATE TABLE `" + table + "`", 2).getFirst());
                if (!strings(statement, "SELECT 1 FROM `" + table + "` LIMIT 1").isEmpty()) {
                    statements.add("INSERT INTO `" + table + "` SELECT * FROM `" + source + "`.`" + table + "`");
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the schema template for " + target, e);
        }
        statements.add("SET FOREIGN_KEY_CHECKS = 1");
        return List.copyOf(statements);
    }

    private static List<String> strings(Statement statement, String sql) throws SQLException {
        return strings(statement, sql, 1);
    }

    private static List<String> strings(Statement statement, String sql, int column) throws SQLException {
        List<String> values = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(column));
            }
        }
        return values;
    }

    private SqlDatabaseProperties copy() {
        String copyName = TestDialects.newDatabaseName();
        if (target.dialect() == DatabaseDialect.POSTGRESQL) {
            TestDialects.execute(target, "CREATE DATABASE " + copyName + " TEMPLATE " + name);
            return TestDialects.database(target, copyName);
        }
        TestDialects.execute(target, "CREATE DATABASE " + copyName);
        SqlDatabaseProperties copy = TestDialects.database(target, copyName);
        try (Connection connection = TestDialects.connect(target, copy); Statement statement = connection.createStatement()) {
            for (String sql : copyStatements) {
                statement.execute(sql);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not copy the schema template into " + copyName + " on " + target, e);
        }
        return copy;
    }
}
