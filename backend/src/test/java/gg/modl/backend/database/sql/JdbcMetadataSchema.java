package gg.modl.backend.database.sql;

import gg.modl.backend.database.DatabaseDialect;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.jooq.impl.QOM.ForeignKeyRule;

final class JdbcMetadataSchema {
    private static final String MYSQL_COLUMN_DETAILS = "SELECT COLUMN_NAME, COLUMN_DEFAULT, COLLATION_NAME FROM information_schema.COLUMNS "
        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?";
    private static final String POSTGRESQL_COLUMN_DETAILS = "SELECT column_name, column_default, collation_name FROM information_schema.columns "
        + "WHERE table_schema = current_schema() AND table_name = ?";
    private static final String VARCHAR = "varchar";
    private static final Map<Integer, ForeignKeyRule> RULES = Map.of(
        DatabaseMetaData.importedKeyCascade, ForeignKeyRule.CASCADE,
        DatabaseMetaData.importedKeySetNull, ForeignKeyRule.SET_NULL,
        DatabaseMetaData.importedKeyNoAction, ForeignKeyRule.NO_ACTION,
        DatabaseMetaData.importedKeyRestrict, ForeignKeyRule.RESTRICT,
        DatabaseMetaData.importedKeySetDefault, ForeignKeyRule.SET_DEFAULT);

    private final Connection connection;
    private final DatabaseMetaData metadata;
    private final DatabaseDialect dialect;
    @Nullable
    private final String catalog;
    @Nullable
    private final String schema;

    private JdbcMetadataSchema(Connection connection, DatabaseDialect dialect) throws SQLException {
        this.connection = connection;
        this.metadata = connection.getMetaData();
        this.dialect = dialect;
        this.catalog = connection.getCatalog();
        this.schema = connection.getSchema();
    }

    static LiveSchema read(Connection connection, DatabaseDialect dialect) throws SQLException {
        return new JdbcMetadataSchema(connection, dialect).read();
    }

    private LiveSchema read() throws SQLException {
        Map<String, TableShape> tables = new TreeMap<>();
        Map<String, String> collations = new TreeMap<>();
        for (String table : tableNames()) {
            Map<String, ColumnDetail> details = columnDetails(table);
            details.forEach((column, detail) -> {
                if (detail.collation() != null) {
                    collations.put(LiveSchema.columnKey(table, column), detail.collation());
                }
            });
            PrimaryKey primaryKey = primaryKey(table);
            tables.put(table, new TableShape(table, columns(table, details), primaryKey.columns(), indexes(table, primaryKey.name()),
                foreignKeys(table)));
        }
        return new LiveSchema(tables, collations, Set.of());
    }

    private List<String> tableNames() throws SQLException {
        List<String> names = new ArrayList<>();
        try (ResultSet rows = metadata.getTables(catalog, schema, "%", new String[] {"TABLE"})) {
            while (rows.next()) {
                String name = rows.getString("TABLE_NAME");
                if (!name.equals(ModlSchema.HISTORY_TABLE)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private Map<String, ColumnDetail> columnDetails(String table) throws SQLException {
        String sql = dialect.isMysqlFamily() ? MYSQL_COLUMN_DETAILS : POSTGRESQL_COLUMN_DETAILS;
        Map<String, ColumnDetail> details = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    details.put(rows.getString(1), new ColumnDetail(rows.getString(2), rows.getString(3)));
                }
            }
        }
        return details;
    }

    private List<TableShape.Column> columns(String table, Map<String, ColumnDetail> details) throws SQLException {
        Map<Integer, TableShape.Column> columns = new TreeMap<>();
        try (ResultSet rows = metadata.getColumns(catalog, schema, table, "%")) {
            while (rows.next()) {
                String name = rows.getString("COLUMN_NAME");
                Class<?> javaType = ColumnTypes.generatedType(table, name, rows.getInt("DATA_TYPE"));
                int length = VARCHAR.equalsIgnoreCase(rows.getString("TYPE_NAME")) ? rows.getInt("COLUMN_SIZE") : 0;
                boolean nullable = "YES".equals(rows.getString("IS_NULLABLE"));
                String defaultValue = ColumnTypes.normalizedDefault(details.get(name).defaultValue(), javaType);
                columns.put(rows.getInt("ORDINAL_POSITION"), new TableShape.Column(name, javaType, nullable, length, defaultValue));
            }
        }
        return List.copyOf(columns.values());
    }

    private PrimaryKey primaryKey(String table) throws SQLException {
        Map<Integer, String> columns = new TreeMap<>();
        String name = null;
        try (ResultSet rows = metadata.getPrimaryKeys(catalog, schema, table)) {
            while (rows.next()) {
                columns.put(rows.getInt("KEY_SEQ"), rows.getString("COLUMN_NAME"));
                name = rows.getString("PK_NAME");
            }
        }
        return new PrimaryKey(name, List.copyOf(columns.values()));
    }

    private Set<TableShape.Index> indexes(String table, @Nullable String primaryKeyName) throws SQLException {
        Map<String, Map<Integer, String>> columns = new LinkedHashMap<>();
        Map<String, Boolean> unique = new HashMap<>();
        try (ResultSet rows = metadata.getIndexInfo(catalog, schema, table, false, false)) {
            while (rows.next()) {
                String name = rows.getString("INDEX_NAME");
                if (rows.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic || name == null || name.equals(primaryKeyName)) {
                    continue;
                }
                columns.computeIfAbsent(name, key -> new TreeMap<>()).put(rows.getInt("ORDINAL_POSITION"), rows.getString("COLUMN_NAME"));
                unique.put(name, !rows.getBoolean("NON_UNIQUE"));
            }
        }
        Set<TableShape.Index> indexes = new HashSet<>();
        columns.forEach((name, ordered) -> indexes.add(new TableShape.Index(name, List.copyOf(ordered.values()), unique.get(name))));
        return indexes;
    }

    private Set<TableShape.ForeignKey> foreignKeys(String table) throws SQLException {
        Map<String, List<ImportedColumn>> keys = new LinkedHashMap<>();
        try (ResultSet rows = metadata.getImportedKeys(catalog, schema, table)) {
            while (rows.next()) {
                keys.computeIfAbsent(rows.getString("FK_NAME"), name -> new ArrayList<>()).add(new ImportedColumn(
                    rows.getInt("KEY_SEQ"), rows.getString("FKCOLUMN_NAME"), rows.getString("PKTABLE_NAME"),
                    rows.getString("PKCOLUMN_NAME"), RULES.get((int) rows.getShort("DELETE_RULE"))));
            }
        }
        Set<TableShape.ForeignKey> foreignKeys = new HashSet<>();
        keys.forEach((name, imported) -> foreignKeys.add(foreignKey(name, imported)));
        return foreignKeys;
    }

    private static TableShape.ForeignKey foreignKey(String name, List<ImportedColumn> imported) {
        List<ImportedColumn> ordered = imported.stream().sorted((left, right) -> Integer.compare(left.sequence(), right.sequence())).toList();
        ImportedColumn first = ordered.getFirst();
        return new TableShape.ForeignKey(name, ordered.stream().map(ImportedColumn::column).toList(), first.referencedTable(),
            ordered.stream().map(ImportedColumn::referencedColumn).toList(), first.deleteRule());
    }

    private record ColumnDetail(@Nullable String defaultValue, @Nullable String collation) {
    }

    private record PrimaryKey(@Nullable String name, List<String> columns) {
    }

    private record ImportedColumn(int sequence, String column, String referencedTable, String referencedColumn, ForeignKeyRule deleteRule) {
    }
}
