package gg.modl.backend.database;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.impl.DSL;

public final class ChildRows {
    private final DSLContext dsl;
    private final DatabaseDialect dialect;
    private final InsertIfAbsent insertIfAbsent;

    public ChildRows(DSLContext dsl, DatabaseDialect dialect, InsertIfAbsent insertIfAbsent) {
        this.dsl = dsl;
        this.dialect = dialect;
        this.insertIfAbsent = insertIfAbsent;
    }

    public <R extends Record> int append(Runnable lockParent, Table<R> table, TableField<R, String> parentKey,
                                         TableField<R, Integer> seq, R row) {
        UnitFrame.requireWrite();
        lockParent.run();
        Integer next = dsl.select(DSL.coalesce(DSL.max(seq), 0).plus(1))
            .from(table)
            .where(parentKey.eq(row.get(parentKey)))
            .fetchSingle()
            .value1();
        row.set(seq, next);
        if (!insertIfAbsent.onPrimaryKey(table).insert(row)) {
            throw new ConcurrentUpdateException("Another append to " + table.getName() + " took sequence " + next);
        }
        return next;
    }

    public void lockRow(Table<?> table, Field<String> key, String value) {
        UnitFrame.requireWrite();
        if (dialect.isExternal()) {
            dsl.selectOne().from(table).where(key.eq(value)).forUpdate().fetch();
        }
    }
}
