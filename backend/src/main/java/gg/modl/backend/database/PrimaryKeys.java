package gg.modl.backend.database;

import java.util.List;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.UniqueKey;

public final class PrimaryKeys {
    private PrimaryKeys() {
    }

    public static <R extends Record> UniqueKey<R> of(Table<R> table) {
        UniqueKey<R> primaryKey = table.getPrimaryKey();
        if (primaryKey == null) {
            throw new IllegalArgumentException(table.getName() + " has no primary key");
        }
        return primaryKey;
    }

    public static List<Field<?>> fieldsOf(Table<?> table) {
        return List.copyOf(of(table).getFields());
    }
}
