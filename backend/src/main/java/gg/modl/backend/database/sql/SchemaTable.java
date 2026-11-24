package gg.modl.backend.database.sql;

import java.util.List;
import java.util.Set;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;

public record SchemaTable(String name, List<SchemaColumn> columns, Set<String> parents) {
    public SchemaTable {
        columns = List.copyOf(columns);
        parents = Set.copyOf(parents);
    }

    public SchemaTable withColumns(List<SchemaColumn> orderedColumns) {
        return new SchemaTable(name, orderedColumns, parents);
    }

    Table<Record> table() {
        return DSL.table(DSL.name(name));
    }

    List<Field<?>> fields() {
        return columns.stream()
            .<Field<?>>map(column -> DSL.field(DSL.name(column.name()), column.type().dataType()))
            .toList();
    }
}
