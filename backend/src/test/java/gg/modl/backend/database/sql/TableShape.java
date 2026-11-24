package gg.modl.backend.database.sql;

import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.jooq.impl.QOM.ForeignKeyRule;

record TableShape(String name, List<TableShape.Column> columns, List<String> primaryKey, Set<TableShape.Index> indexes,
                  Set<TableShape.ForeignKey> foreignKeys) {
    record Column(String name, Class<?> javaType, boolean nullable, int length, @Nullable String defaultValue) {
    }

    record Index(String name, List<String> columns, boolean unique) {
    }

    record ForeignKey(@Nullable String name, List<String> columns, String referencedTable, List<String> referencedColumns,
                      ForeignKeyRule deleteRule) {
        ForeignKey unnamed() {
            return new ForeignKey(null, columns, referencedTable, referencedColumns, deleteRule);
        }
    }
}
