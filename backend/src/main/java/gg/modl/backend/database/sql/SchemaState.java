package gg.modl.backend.database.sql;

import gg.modl.backend.database.DatabaseDialect;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.jooq.DSLContext;

record SchemaState(
    Set<String> tables,
    Optional<String> version,
    boolean unfinished,
    boolean listsRestoreTables,
    Set<String> restoreTables
) {
    SchemaState {
        tables = Set.copyOf(tables);
        restoreTables = Set.copyOf(restoreTables);
    }

    static SchemaState read(DatabaseAccess access, Connection connection, DatabaseDialect dialect, DataSource connections)
        throws SQLException {
        Set<String> names = SchemaCatalog.allTableNames(connection, dialect);
        boolean hasHistory = names.remove(ModlSchema.HISTORY_TABLE);
        boolean unfinished = names.remove(ModlSchema.UNFINISHED_TABLE);
        boolean listed = names.remove(ModlSchema.RESTORE_TABLES);
        DSLContext dsl = access.queries(connection, dialect);
        Optional<String> version = hasHistory && ModlSchema.isModlHistory(access, connection, dialect, connections)
            ? ModlSchema.currentVersion(dsl)
            : Optional.empty();
        Set<String> restoreTables = listed ? RestoreTableList.read(dsl) : Set.of();
        return new SchemaState(names, version, unfinished, listed, restoreTables);
    }

    Set<String> ownedTables(MigratedSchemas schemas, Set<String> newerVersionTables) {
        return provenTables(schemas, newerVersionTables).stream().filter(tables::contains).collect(Collectors.toUnmodifiableSet());
    }

    Set<String> ownedControlTables() {
        Set<String> control = new HashSet<>(Set.of(ModlSchema.UNFINISHED_TABLE, ModlSchema.RESTORE_TABLES));
        if (version.isPresent()) {
            control.add(ModlSchema.HISTORY_TABLE);
        }
        return control;
    }

    boolean isNewerThanThisJar(MigratedSchemas schemas) {
        return version.filter(schemas::isNewerThanThisJar).isPresent();
    }

    private Set<String> provenTables(MigratedSchemas schemas, Set<String> newerVersionTables) {
        if (version.isEmpty()) {
            return unfinished ? restoreTables : Set.of();
        }
        Set<String> proven = new HashSet<>(isNewerThanThisJar(schemas)
            ? schemas.latest().tables()
            : schemas.through(version.get()).tables());
        proven.addAll(newerVersionTables);
        return proven;
    }
}
