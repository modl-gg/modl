package gg.modl.backend.database;

import org.jooq.Field;
import org.jooq.Table;

public final class DerivedColumns {
    private DerivedColumns() {
    }

    public static <T> Field<T> of(Table<?> derived, Field<T> column) {
        Field<T> resolved = derived.field(column);
        if (resolved == null) {
            throw new IllegalStateException("Derived table " + derived.getName() + " lacks column " + column.getName());
        }
        return resolved;
    }
}
