package gg.modl.backend.database.sql;

import gg.modl.backend.database.DatabaseDialect;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

final class RestoreBlockers {
    private static final String SQLITE_NAMES = "SELECT name, tbl_name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'";
    private static final String MYSQL_TABLES = "SELECT table_name, table_name FROM information_schema.tables "
        + "WHERE table_schema = DATABASE()";
    private static final String MYSQL_FOREIGN_KEYS = "SELECT constraint_name, table_name FROM information_schema.table_constraints "
        + "WHERE constraint_schema = DATABASE() AND constraint_type = 'FOREIGN KEY'";
    private static final String MYSQL_REFERENCES = "SELECT DISTINCT table_schema, table_name, referenced_table_name, DATABASE() "
        + "FROM information_schema.key_column_usage WHERE referenced_table_schema = DATABASE()";
    private static final String POSTGRESQL_RELATIONS = "SELECT c.relname, COALESCE(t.relname, c.relname) FROM pg_class c "
        + "JOIN pg_namespace n ON n.oid = c.relnamespace LEFT JOIN pg_index i ON i.indexrelid = c.oid "
        + "LEFT JOIN pg_class t ON t.oid = i.indrelid WHERE n.nspname = current_schema()";
    private static final String POSTGRESQL_TYPES = "SELECT t.typname, t.typname FROM pg_type t "
        + "JOIN pg_namespace n ON n.oid = t.typnamespace WHERE n.nspname = current_schema() AND t.typtype IN ('d', 'e', 'r')";
    private static final String POSTGRESQL_VIEWS = "SELECT DISTINCT v.relname, t.relname FROM pg_depend d "
        + "JOIN pg_rewrite r ON r.oid = d.objid JOIN pg_class v ON v.oid = r.ev_class JOIN pg_class t ON t.oid = d.refobjid "
        + "JOIN pg_namespace n ON n.oid = t.relnamespace WHERE n.nspname = current_schema() "
        + "AND d.classid = 'pg_rewrite'::regclass AND d.refclassid = 'pg_class'::regclass AND v.oid <> t.oid";
    private static final String POSTGRESQL_HEIRS = "SELECT c.relname, t.relname FROM pg_inherits i "
        + "JOIN pg_class c ON c.oid = i.inhrelid JOIN pg_class t ON t.oid = i.inhparent "
        + "JOIN pg_namespace n ON n.oid = t.relnamespace WHERE n.nspname = current_schema()";

    private final DatabaseAccess access;
    private final DatabaseDialect dialect;
    private final SqlStatements statements;

    RestoreBlockers(DatabaseAccess access, DatabaseDialect dialect) {
        this.access = access;
        this.dialect = dialect;
        this.statements = access.statements();
    }

    void requireNone(Connection connection, SchemaState current, SchemaCatalog replacement, Set<String> newerVersionTables)
        throws SQLException {
        Set<String> owned = current.ownedTables(access.schemas(), newerVersionTables);
        Set<String> blockers = new TreeSet<>(takenNames(connection, current, owned, replacement));
        blockers.addAll(dependents(connection, owned));
        boolean unrecognised = current.isNewerThanThisJar(access.schemas()) && newerVersionTables.isEmpty();
        if (unrecognised) {
            current.tables().stream().filter(table -> !owned.contains(table)).forEach(blockers::add);
        }
        if (blockers.isEmpty()) {
            return;
        }
        String remedy = unrecognised
            ? "The database has the schema of a newer modl, so some of them may be its tables: run the command with the jar of that"
                + " version, or move the ones that are not modl's to another database."
            : "Rename them, move them to another database, or give modl a database of its own.";
        throw new DatabaseToolException("Nothing was changed, because modl does not own these and they block it: "
            + String.join(", ", blockers) + ". modl never changes or deletes them. " + remedy);
    }

    private Set<String> takenNames(Connection connection, SchemaState current, Set<String> owned, SchemaCatalog replacement)
        throws SQLException {
        Set<String> needed = new HashSet<>(access.schemas().latest().schemaWideNames(dialect));
        needed.addAll(replacement.tableNames());
        Set<String> neededIgnoringCase = needed.stream().map(RestoreBlockers::folded).collect(Collectors.toSet());
        Set<String> modls = new HashSet<>(owned);
        modls.addAll(current.ownedControlTables());
        Set<String> taken = new TreeSet<>();
        for (List<String> named : schemaWideNames(connection)) {
            String name = named.get(0);
            String owner = named.get(1);
            if (!modls.contains(owner) && neededIgnoringCase.contains(folded(name))) {
                taken.add(name.equals(owner) ? name : owner + " (its " + name + ")");
            }
        }
        return taken;
    }

    private List<List<String>> schemaWideNames(Connection connection) throws SQLException {
        return switch (dialect) {
            case SQLITE -> statements.rows(connection, SQLITE_NAMES);
            case MYSQL, MARIADB -> bothOf(connection, MYSQL_TABLES, MYSQL_FOREIGN_KEYS);
            case POSTGRESQL -> bothOf(connection, POSTGRESQL_RELATIONS, POSTGRESQL_TYPES);
        };
    }

    private Set<String> dependents(Connection connection, Set<String> owned) throws SQLException {
        Set<String> dependents = new TreeSet<>(dialect.isMysqlFamily()
            ? mysqlReferencingTables(connection, owned)
            : SchemaCatalog.referencingTables(connection, dialect, owned));
        if (dialect == DatabaseDialect.POSTGRESQL) {
            for (List<String> dependency : bothOf(connection, POSTGRESQL_VIEWS, POSTGRESQL_HEIRS)) {
                if (owned.contains(dependency.get(1)) && !owned.contains(dependency.get(0))) {
                    dependents.add(dependency.get(0));
                }
            }
        }
        return dependents;
    }

    private Set<String> mysqlReferencingTables(Connection connection, Set<String> owned) throws SQLException {
        Set<String> referencing = new TreeSet<>();
        for (List<String> reference : statements.rows(connection, MYSQL_REFERENCES)) {
            String schema = reference.get(0);
            String table = reference.get(1);
            boolean elsewhere = !schema.equals(reference.get(3));
            if (owned.contains(reference.get(2)) && (elsewhere || !owned.contains(table))) {
                referencing.add(elsewhere ? schema + "." + table : table);
            }
        }
        return referencing;
    }

    private List<List<String>> bothOf(Connection connection, String first, String second) throws SQLException {
        List<List<String>> rows = statements.rows(connection, first);
        rows.addAll(statements.rows(connection, second));
        return rows;
    }

    private static String folded(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
