package gg.modl.backend.database.sql;

import gg.modl.backend.database.DatabaseDialect;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class PrivilegeProbe {
    private static final int SUFFIX_BYTES = 4;
    private static final String PARENT_PREFIX = "modl_probe_parent_";
    private static final String CHILD_PREFIX = "modl_probe_child_";
    private static final Pattern LEFTOVER = Pattern.compile("modl_probe_(parent|child)_[0-9a-f]{" + SUFFIX_BYTES * 2 + "}");

    private final SqlStatements statements;
    private final DatabaseDialect dialect;

    PrivilegeProbe(SqlStatements statements, DatabaseDialect dialect) {
        this.statements = statements;
        this.dialect = dialect;
    }

    List<String> missing(Connection connection) {
        dropLeftovers(connection);
        String suffix = HexFormat.of().formatHex(randomBytes());
        String parent = PARENT_PREFIX + suffix;
        String child = CHILD_PREFIX + suffix;
        List<Step> steps = List.of(
            new Step("CREATE", "CREATE TABLE " + parent + " (id INTEGER NOT NULL PRIMARY KEY)"),
            new Step("REFERENCES", "CREATE TABLE " + child + " (id INTEGER NOT NULL PRIMARY KEY, parent_id INTEGER NOT NULL,"
                + " CONSTRAINT modl_fk_probe_" + suffix + " FOREIGN KEY (parent_id) REFERENCES " + parent + " (id))"),
            new Step("INDEX", "CREATE INDEX modl_idx_probe_" + suffix + " ON " + child + " (parent_id)"),
            new Step("ALTER", "ALTER TABLE " + child + " ADD COLUMN note VARCHAR(16)"),
            new Step("INSERT", "INSERT INTO " + parent + " (id) VALUES (1)"),
            new Step("INSERT", "INSERT INTO " + child + " (id, parent_id) VALUES (1, 1)"),
            new Step("UPDATE", "UPDATE " + child + " SET note = 'probe' WHERE id = 1"),
            new Step("SELECT", "SELECT note FROM " + child + " WHERE id = 1"),
            new Step("DELETE", "DELETE FROM " + child + " WHERE id = 1"));
        List<String> missing = new ArrayList<>();
        for (Step step : steps) {
            if (!succeeds(connection, step.sql())) {
                missing.add(step.privilege());
                break;
            }
        }
        boolean droppedChild = succeeds(connection, "DROP TABLE " + child) || !exists(connection, child);
        boolean droppedParent = succeeds(connection, "DROP TABLE " + parent) || !exists(connection, parent);
        if (!droppedChild || !droppedParent) {
            missing.add("DROP");
        }
        return List.copyOf(missing);
    }

    private void dropLeftovers(Connection connection) {
        List<String> leftovers;
        try {
            leftovers = SchemaCatalog.allTableNames(connection, dialect).stream()
                .filter(name -> LEFTOVER.matcher(name).matches())
                .sorted(Comparator.comparing((String name) -> !name.startsWith(CHILD_PREFIX)).thenComparing(Comparator.naturalOrder()))
                .toList();
        } catch (SQLException e) {
            log.debug("The tables of an earlier privilege probe could not be listed: {}", e.getMessage());
            return;
        }
        leftovers.forEach(table -> succeeds(connection, "DROP TABLE " + table));
    }

    private boolean succeeds(Connection connection, String sql) {
        try {
            statements.execute(connection, sql);
            return true;
        } catch (SQLException e) {
            log.debug("The privilege probe statement failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean exists(Connection connection, String table) {
        try {
            statements.execute(connection, "SELECT 1 FROM " + table + " WHERE 1 = 0");
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[SUFFIX_BYTES];
        ThreadLocalRandom.current().nextBytes(bytes);
        return bytes;
    }

    private record Step(String privilege, String sql) {
    }
}
