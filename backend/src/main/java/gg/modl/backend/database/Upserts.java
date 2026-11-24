package gg.modl.backend.database;

import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Index;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.UniqueKey;

public final class Upserts {
    private final DSLContext dsl;
    private final DatabaseDialect dialect;

    public Upserts(DSLContext dsl, DatabaseDialect dialect) {
        this.dsl = dsl;
        this.dialect = dialect;
    }

    public <R extends Record> void onPrimaryKey(Table<R> table, R row, Map<? extends Field<?>, ?> assignments) {
        UnitFrame.requireWrite();
        UniqueKey<R> primaryKey = requireSoleUniqueKeyIsPrimary(table);
        if (assignments.isEmpty()) {
            throw new IllegalArgumentException("An upsert of " + table.getName() + " needs explicit update assignments");
        }
        if (dialect.isMysqlFamily()) {
            dsl.insertInto(table).set(row).onDuplicateKeyUpdate().set(assignments).execute();
            return;
        }
        dsl.insertInto(table).set(row)
            .onConflict(primaryKey.getFields())
            .doUpdate()
            .set(assignments)
            .execute();
    }

    static <R extends Record> UniqueKey<R> requireSoleUniqueKeyIsPrimary(Table<R> table) {
        UniqueKey<R> primaryKey = table.getPrimaryKey();
        boolean onlyPrimaryKey = table.getKeys().size() == 1;
        boolean noUniqueIndex = table.getIndexes().stream().noneMatch(Index::getUnique);
        if (primaryKey == null || !onlyPrimaryKey || !noUniqueIndex) {
            throw new IllegalArgumentException("Upserts.onPrimaryKey needs a table whose only unique key is its primary key, "
                + "but " + table.getName() + " has others");
        }
        return primaryKey;
    }
}
