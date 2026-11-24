package gg.modl.backend.database.sql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jooq.impl.QOM.ForeignKeyRule;

final class SqlitePragmaSchema {
    private static final String TABLES = "SELECT name, wr FROM pragma_table_list WHERE schema = 'main' AND type = 'table' "
        + "AND substr(name, 1, 7) <> 'sqlite_' AND name <> ?";
    private static final String COLUMNS = "SELECT name, type, \"notnull\", dflt_value, pk FROM pragma_table_info(?) ORDER BY cid";
    private static final String INDEXES = "SELECT name, \"unique\" FROM pragma_index_list(?) WHERE origin <> 'pk'";
    private static final String INDEX_COLUMNS = "SELECT name FROM pragma_index_info(?) ORDER BY seqno";
    private static final String FOREIGN_KEYS = "SELECT id, \"table\", \"from\", \"to\", on_delete FROM pragma_foreign_key_list(?) ORDER BY id, seq";
    private static final String TABLE_SQL = "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?";
    private static final String AUTOINDEX_PREFIX = "sqlite_autoindex_";
    private static final String DEFAULT_COLLATION = "BINARY";
    private static final Pattern DECLARED_TYPE = Pattern.compile("([A-Z]+)(?:\\((\\d+)\\))?");
    private static final Pattern ELEMENT_NAME = Pattern.compile("\"?(\\w+)\"?\\s");
    private static final Pattern COLLATE =Pattern.compile("(?i)\\bCOLLATE\\s+\"?(\\w+)\"?");
    private static final Map<String, Integer> SQL_TYPES = Map.of(
        "BIGINT", Types.BIGINT,
        "INTEGER", Types.INTEGER,
        "BOOLEAN", Types.BOOLEAN,
        "VARCHAR", Types.VARCHAR,
        "TEXT", Types.CLOB,
        "BLOB", Types.BLOB);

    private SqlitePragmaSchema() {
    }

    static LiveSchema read(Connection connection) throws SQLException {
        Map<String, TableShape> tables = new TreeMap<>();
        Map<String, String> collations = new TreeMap<>();
        Set<String> withoutRowid = new TreeSet<>();
        try (PreparedStatement statement = connection.prepareStatement(TABLES)) {
            statement.setString(1, ModlSchema.HISTORY_TABLE);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String table = rows.getString("name");
                    if (rows.getBoolean("wr")) {
                        withoutRowid.add(table);
                    }
                    tables.put(table, table(connection, table, collations));
                }
            }
        }
        return new LiveSchema(tables, collations, withoutRowid);
    }

    private static TableShape table(Connection connection, String table, Map<String, String> collations) throws SQLException {
        String definition = single(connection, TABLE_SQL, table);
        List<TableShape.Column> columns = new ArrayList<>();
        Map<Integer, String> primaryKey = new TreeMap<>();
        try (PreparedStatement statement = prepared(connection, COLUMNS, table); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                TableShape.Column column = column(table, rows);
                columns.add(column);
                if (rows.getInt("pk") > 0) {
                    primaryKey.put(rows.getInt("pk"), column.name());
                }
                if (ColumnTypes.isText(column.javaType())) {
                    collations.put(LiveSchema.columnKey(table, column.name()), collation(definition, column.name()));
                }
            }
        }
        return new TableShape(table, columns, List.copyOf(primaryKey.values()), indexes(connection, table), foreignKeys(connection, table));
    }

    private static TableShape.Column column(String table, ResultSet row) throws SQLException {
        String name = row.getString("name");
        Matcher declared = DECLARED_TYPE.matcher(row.getString("type").toUpperCase(Locale.ROOT));
        if (!declared.matches() || !SQL_TYPES.containsKey(declared.group(1))) {
            throw new IllegalStateException("Unmapped SQLite type " + row.getString("type") + " on " + table + "." + name);
        }
        Class<?> javaType = ColumnTypes.generatedType(table, name, SQL_TYPES.get(declared.group(1)));
        int length = declared.group(2) == null ? 0 : Integer.parseInt(declared.group(2));
        return new TableShape.Column(name, javaType, row.getInt("notnull") == 0, length,
            ColumnTypes.normalizedDefault(row.getString("dflt_value"), javaType));
    }

    private static Set<TableShape.Index> indexes(Connection connection, String table) throws SQLException {
        Set<TableShape.Index> indexes = new HashSet<>();
        try (PreparedStatement statement = prepared(connection, INDEXES, table); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String name = rows.getString("name");
                if (!name.startsWith(AUTOINDEX_PREFIX)) {
                    indexes.add(new TableShape.Index(name, strings(connection, INDEX_COLUMNS, name), rows.getBoolean("unique")));
                }
            }
        }
        return indexes;
    }

    private static Set<TableShape.ForeignKey> foreignKeys(Connection connection, String table) throws SQLException {
        Map<Integer, ForeignKeyColumns> keys = new TreeMap<>();
        try (PreparedStatement statement = prepared(connection, FOREIGN_KEYS, table); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                ForeignKeyColumns key = keys.computeIfAbsent(rows.getInt("id"), id -> new ForeignKeyColumns());
                key.referencedTable = rows.getString("table");
                key.deleteRule = rule(rows.getString("on_delete"));
                key.columns.add(rows.getString("from"));
                key.referencedColumns.add(rows.getString("to"));
            }
        }
        Set<TableShape.ForeignKey> foreignKeys = new HashSet<>();
        for (ForeignKeyColumns key : keys.values()) {
            foreignKeys.add(new TableShape.ForeignKey(null, List.copyOf(key.columns), key.referencedTable,
                List.copyOf(key.referencedColumns), key.deleteRule));
        }
        return foreignKeys;
    }

    private static ForeignKeyRule rule(String onDelete) {
        return ForeignKeyRule.valueOf(onDelete.toUpperCase(Locale.ROOT).replace(' ', '_'));
    }

    private static String collation(String tableDefinition, String column) {
        String definition = columnDefinition(tableDefinition, column);
        Matcher collate = COLLATE.matcher(definition);
        return collate.find() ? collate.group(1) : DEFAULT_COLLATION;
    }

    private static String columnDefinition(String tableDefinition, String column) {
        for (String element : tableElements(tableDefinition)) {
            Matcher name = ELEMENT_NAME.matcher(element);
            if (name.lookingAt() && name.group(1).equalsIgnoreCase(column)) {
                return element;
            }
        }
        throw new IllegalStateException("No definition of column " + column + " in " + tableDefinition);
    }

    private static List<String> tableElements(String tableDefinition) {
        int open = tableDefinition.indexOf('(');
        int close = tableDefinition.lastIndexOf(')');
        if (open < 0 || close < open) {
            throw new IllegalStateException("Unparseable table definition " + tableDefinition);
        }
        List<String> elements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        char quote = 0;
        for (char character : tableDefinition.substring(open + 1, close).toCharArray()) {
            if (quote != 0) {
                quote = character == quote ? 0 : quote;
            } else if (character == '\'' || character == '"') {
                quote = character;
            } else if (character == '(') {
                depth++;
            } else if (character == ')') {
                depth--;
            } else if (character == ',' && depth == 0) {
                elements.add(current.toString().strip());
                current.setLength(0);
                continue;
            }
            current.append(character);
        }
        elements.add(current.toString().strip());
        return elements;
    }

    private static List<String> strings(Connection connection, String sql, String argument) throws SQLException {
        List<String> values = new ArrayList<>();
        try (PreparedStatement statement = prepared(connection, sql, argument); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }

    private static String single(Connection connection, String sql, String argument) throws SQLException {
        List<String> values = strings(connection, sql, argument);
        if (values.size() != 1) {
            throw new IllegalStateException(sql + " returned " + values + " for " + argument);
        }
        return values.getFirst();
    }

    private static PreparedStatement prepared(Connection connection, String sql, String argument) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setString(1, argument);
        return statement;
    }

    private static final class ForeignKeyColumns {
        private final List<String> columns = new ArrayList<>();
        private final List<String> referencedColumns = new ArrayList<>();
        private String referencedTable;
        private ForeignKeyRule deleteRule;
    }
}
