package gg.modl.backend.database.backup;

import gg.modl.backend.database.sql.ColumnType;
import gg.modl.backend.database.sql.SchemaColumn;

record ArchivedColumn(String name, ColumnType type) {
    static ArchivedColumn of(SchemaColumn column) {
        return new ArchivedColumn(column.name(), column.type());
    }
}
