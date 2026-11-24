package gg.modl.backend.database.sql;

import gg.modl.backend.database.DatabaseDialect;
import java.util.HashSet;
import java.util.Set;

record MigratedSchema(String version, Set<String> tables, Set<String> indexes, Set<String> foreignKeys) {
    private static final String POSTGRESQL_KEY_SUFFIX = "_pkey";

    MigratedSchema {
        tables = Set.copyOf(tables);
        indexes = Set.copyOf(indexes);
        foreignKeys = Set.copyOf(foreignKeys);
    }

    Set<String> schemaWideNames(DatabaseDialect dialect) {
        Set<String> names = new HashSet<>(tables);
        names.add(ModlSchema.HISTORY_TABLE);
        switch (dialect) {
            case SQLITE -> names.addAll(indexes);
            case MYSQL, MARIADB -> names.addAll(foreignKeys);
            case POSTGRESQL -> {
                names.addAll(indexes);
                tables.forEach(table -> names.add(table + POSTGRESQL_KEY_SUFFIX));
            }
        }
        return names;
    }
}
