package gg.modl.backend.database.backup;

import gg.modl.backend.database.sql.SchemaColumn;
import gg.modl.backend.database.sql.SchemaTable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

record ArchivedTable(String name, long rows, List<ArchivedColumn> columns) {
    ArchivedTable {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }

    static ArchivedTable of(SchemaTable table, long rows) {
        return new ArchivedTable(table.name(), rows, table.columns().stream().map(ArchivedColumn::of).toList());
    }

    SchemaTable inArchivedOrder(SchemaTable schema) throws InvalidArchiveException {
        Map<String, SchemaColumn> byName = schema.columns().stream()
            .collect(Collectors.toMap(SchemaColumn::name, Function.identity()));
        List<SchemaColumn> ordered = new ArrayList<>();
        for (ArchivedColumn archived : columns) {
            SchemaColumn column = byName.remove(archived.name());
            if (column == null || column.type() != archived.type()) {
                throw mismatch();
            }
            ordered.add(column);
        }
        if (!byName.isEmpty()) {
            throw mismatch();
        }
        return schema.withColumns(ordered);
    }

    private InvalidArchiveException mismatch() {
        return InvalidArchiveException.damaged("the columns of " + name + " do not match its database schema version");
    }
}
