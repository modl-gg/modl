package gg.modl.backend.database.sql;

import gg.modl.backend.database.DatabaseDialect;
import org.jspecify.annotations.Nullable;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

public record SchemaCatalog(List<SchemaTable> tables) {
    private static final String SQLITE_INTERNAL_PREFIX = "sqlite_";
    private static final String VARCHAR = "VARCHAR";
    private static final String ANY = "%";
    private static final String[] ORDINARY_TABLES = {"TABLE"};

    public SchemaCatalog {
        tables = List.copyOf(tables);
    }

    static SchemaCatalog read(Connection connection, DatabaseDialect dialect, Set<String> modlTables) throws SQLException {
        Scope scope = Scope.of(connection, dialect);
        DatabaseMetaData metadata = connection.getMetaData();
        List<SchemaTable> tables = new ArrayList<>();
        for (String name : new TreeSet<>(modlTables)) {
            tables.add(new SchemaTable(name, columns(metadata, scope, name), parents(metadata, scope, name)));
        }
        return new SchemaCatalog(parentsFirst(tables));
    }

    static List<String> childrenFirst(Connection connection, DatabaseDialect dialect, Set<String> names) throws SQLException {
        Scope scope = Scope.of(connection, dialect);
        DatabaseMetaData metadata = connection.getMetaData();
        List<SchemaTable> tables = new ArrayList<>();
        for (String name : new TreeSet<>(names)) {
            tables.add(new SchemaTable(name, List.of(), parents(metadata, scope, name)));
        }
        return parentsFirst(tables).reversed().stream().map(SchemaTable::name).toList();
    }

    static Set<String> allTableNames(Connection connection, DatabaseDialect dialect) throws SQLException {
        Scope scope = Scope.of(connection, dialect);
        Set<String> names = new TreeSet<>();
        try (ResultSet rows = connection.getMetaData().getTables(scope.catalog(), scope.schema(), ANY, ORDINARY_TABLES)) {
            while (rows.next()) {
                String name = rows.getString("TABLE_NAME");
                if (scope.includes(rows.getString("TABLE_SCHEM")) && !name.startsWith(SQLITE_INTERNAL_PREFIX)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    static Set<String> columnNames(Connection connection, DatabaseDialect dialect, String table) throws SQLException {
        Scope scope = Scope.of(connection, dialect);
        Set<String> names = new TreeSet<>();
        try (ResultSet rows = connection.getMetaData().getColumns(scope.catalog(), scope.schema(), table, ANY)) {
            while (rows.next()) {
                if (table.equals(rows.getString("TABLE_NAME")) && scope.includes(rows.getString("TABLE_SCHEM"))) {
                    names.add(rows.getString("COLUMN_NAME"));
                }
            }
        }
        return names;
    }

    static Set<String> referencingTables(Connection connection, DatabaseDialect dialect, Set<String> referenced) throws SQLException {
        Scope scope = Scope.of(connection, dialect);
        DatabaseMetaData metadata = connection.getMetaData();
        Set<String> referencing = new TreeSet<>();
        for (String table : referenced) {
            try (ResultSet rows = metadata.getExportedKeys(scope.catalog(), scope.schema(), table)) {
                while (rows.next()) {
                    String child = rows.getString("FKTABLE_NAME");
                    String namespace = dialect == DatabaseDialect.POSTGRESQL ? rows.getString("FKTABLE_SCHEM") : null;
                    boolean elsewhere = namespace != null && !namespace.equals(scope.schema());
                    if (elsewhere) {
                        referencing.add(namespace + "." + child);
                    } else if (!referenced.contains(child)) {
                        referencing.add(child);
                    }
                }
            }
        }
        return referencing;
    }

    Set<String> tableNames() {
        return tables.stream().map(SchemaTable::name).collect(Collectors.toUnmodifiableSet());
    }

    private static List<SchemaColumn> columns(DatabaseMetaData metadata, Scope scope, String table) throws SQLException {
        List<SchemaColumn> columns = new ArrayList<>();
        try (ResultSet rows = metadata.getColumns(scope.catalog(), scope.schema(), table, ANY)) {
            while (rows.next()) {
                if (table.equals(rows.getString("TABLE_NAME")) && scope.includes(rows.getString("TABLE_SCHEM"))) {
                    columns.add(column(table, rows.getString("COLUMN_NAME"), rows.getString("TYPE_NAME"), rows.getInt("COLUMN_SIZE")));
                }
            }
        }
        return columns;
    }

    private static SchemaColumn column(String table, String name, String declaredType, int size) {
        ColumnType type = ColumnType.ofDeclared(declaredType).orElseThrow(() -> new DatabaseToolException(
            "The column " + table + "." + name + " has the type " + declaredType + ", which modl cannot back up"));
        return new SchemaColumn(name, type, VARCHAR.equalsIgnoreCase(declaredType) ? size : SchemaColumn.UNLIMITED);
    }

    private static Set<String> parents(DatabaseMetaData metadata, Scope scope, String table) throws SQLException {
        Set<String> parents = new TreeSet<>();
        try (ResultSet rows = metadata.getImportedKeys(scope.catalog(), scope.schema(), table)) {
            while (rows.next()) {
                parents.add(rows.getString("PKTABLE_NAME"));
            }
        }
        parents.remove(table);
        return parents;
    }

    private static List<SchemaTable> parentsFirst(List<SchemaTable> tables) {
        Map<String, SchemaTable> byName = tables.stream().collect(Collectors.toMap(SchemaTable::name, Function.identity()));
        Set<SchemaTable> ordered = new LinkedHashSet<>();
        for (SchemaTable table : tables) {
            visit(table, byName, new TreeSet<>(), ordered);
        }
        return List.copyOf(ordered);
    }

    private static void visit(SchemaTable table, Map<String, SchemaTable> byName, Set<String> visiting, Set<SchemaTable> ordered) {
        if (!visiting.add(table.name())) {
            throw new DatabaseToolException("The tables around " + table.name() + " refer to each other, so they cannot be copied in order");
        }
        for (String parent : table.parents()) {
            SchemaTable referenced = byName.get(parent);
            if (referenced != null && !ordered.contains(referenced)) {
                visit(referenced, byName, visiting, ordered);
            }
        }
        visiting.remove(table.name());
        ordered.add(table);
    }

    private record Scope(@Nullable String catalog, @Nullable String schema) {
        static Scope of(Connection connection, DatabaseDialect dialect) throws SQLException {
            if (dialect != DatabaseDialect.POSTGRESQL) {
                return new Scope(connection.getCatalog(), null);
            }
            String schema = connection.getSchema();
            if (schema == null) {
                throw new DatabaseToolException("The PostgreSQL connection has no schema; set currentSchema in MODL_DATABASE_URL");
            }
            return new Scope(connection.getCatalog(), schema);
        }

        boolean includes(@Nullable String tableSchema) {
            return schema == null || Objects.equals(schema, tableSchema);
        }
    }
}
