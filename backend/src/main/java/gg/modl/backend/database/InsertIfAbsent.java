package gg.modl.backend.database;

import java.util.List;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Index;
import org.jooq.Record;
import org.jooq.SortField;
import org.jooq.Table;
import org.jooq.impl.DSL;

public final class InsertIfAbsent {
    private final DSLContext dsl;
    private final DatabaseDialect dialect;

    public InsertIfAbsent(DSLContext dsl, DatabaseDialect dialect) {
        this.dsl = dsl;
        this.dialect = dialect;
    }

    public <R extends Record> Target<R> on(Table<R> table, Index uniqueIndex) {
        if (!uniqueIndex.getUnique() || !table.equals(uniqueIndex.getTable())) {
            throw new IllegalArgumentException(uniqueIndex.getName() + " is not a unique index of " + table.getName());
        }
        List<Field<?>> keyFields = uniqueIndex.getFields().stream().<Field<?>>map(SortField::$field).toList();
        return new Target<>(table, keyFields);
    }

    public <R extends Record> Target<R> onPrimaryKey(Table<R> table) {
        return new Target<>(table, PrimaryKeys.fieldsOf(table));
    }

    public <R extends Record> AnyUnique<R> onAnyUnique(Table<R> table) {
        return new AnyUnique<>(table);
    }

    public final class Target<R extends Record> {
        private final Table<R> table;
        private final List<Field<?>> keyFields;

        private Target(Table<R> table, List<Field<?>> keyFields) {
            this.table = table;
            this.keyFields = keyFields;
        }

        public boolean insert(R row) {
            UnitFrame.requireWrite();
            if (dialect.isMysqlFamily()) {
                return insertOrFindExisting(row);
            }
            return dsl.insertInto(table).set(row).onConflict(keyFields).doNothing().execute() == 1;
        }

        private boolean insertOrFindExisting(R row) {
            try {
                return dsl.insertInto(table).set(row).execute() == 1;
            } catch (DuplicateRowException conflict) {
                if (dsl.fetchExists(table, keyMatches(row))) {
                    return false;
                }
                throw conflict;
            }
        }

        private Condition keyMatches(R row) {
            return DSL.and(keyFields.stream().map(field -> equalsValueOf(field, row)).toList());
        }

        private <T> Condition equalsValueOf(Field<T> field, R row) {
            T value = row.get(field);
            return value == null ? field.isNull() : field.eq(value);
        }
    }

    public final class AnyUnique<R extends Record> {
        private final Table<R> table;

        private AnyUnique(Table<R> table) {
            this.table = table;
        }

        public boolean insert(R row) {
            UnitFrame.requireWrite();
            if (dialect.isMysqlFamily()) {
                try {
                    return dsl.insertInto(table).set(row).execute() == 1;
                } catch (DuplicateRowException conflict) {
                    return false;
                }
            }
            return dsl.insertInto(table).set(row).onConflictDoNothing().execute() == 1;
        }
    }
}
