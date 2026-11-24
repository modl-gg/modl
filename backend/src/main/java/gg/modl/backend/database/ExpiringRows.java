package gg.modl.backend.database;

import java.time.Instant;
import java.util.List;
import org.jooq.Field;
import org.jooq.Table;

public record ExpiringRows(Table<?> table, Field<Instant> expiresAt) {
    public ExpiringRows {
        PrimaryKeys.of(table);
    }

    public List<Field<?>> key() {
        return PrimaryKeys.fieldsOf(table);
    }
}
