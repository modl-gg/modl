package gg.modl.backend.auth.repository;

import java.util.Optional;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.TableField;

final class SingleUseRows {
    private SingleUseRows() {
    }

    static <R extends Record> Optional<R> consume(DSLContext dsl, Table<R> table, TableField<R, String> key, Condition usable) {
        return dsl.selectFrom(table).where(usable).fetchOptional()
            .filter(row -> dsl.deleteFrom(table).where(key.eq(row.get(key))).and(usable).execute() == 1);
    }
}
