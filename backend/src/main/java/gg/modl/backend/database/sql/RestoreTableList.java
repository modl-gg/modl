package gg.modl.backend.database.sql;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

final class RestoreTableList {
    private static final int MAX_TABLE_NAME_LENGTH = 128;
    private static final Table<Record> LIST = DSL.table(DSL.name(ModlSchema.RESTORE_TABLES));
    private static final Field<String> NAME = DSL.field(DSL.name("table_name"),
        SQLDataType.VARCHAR(MAX_TABLE_NAME_LENGTH).nullable(false));

    private RestoreTableList() {
    }

    static Set<String> read(DSLContext dsl) {
        return dsl.select(NAME).from(LIST).fetch(NAME).stream().collect(Collectors.toUnmodifiableSet());
    }

    static void create(DSLContext dsl) {
        dsl.createTable(LIST).column(NAME).execute();
    }

    static void add(DSLContext dsl, Set<String> tables) {
        Set<String> listed = read(dsl);
        for (String table : tables) {
            if (!listed.contains(table)) {
                dsl.insertInto(LIST).set(NAME, table).execute();
            }
        }
    }

    static void remove(DSLContext dsl, Collection<String> tables) {
        if (!tables.isEmpty()) {
            dsl.deleteFrom(LIST).where(NAME.in(tables)).execute();
        }
    }

    static void retainOnly(DSLContext dsl, Set<String> existing) {
        remove(dsl, read(dsl).stream().filter(table -> !existing.contains(table)).toList());
    }

    static void drop(DSLContext dsl) {
        dsl.dropTable(LIST).execute();
    }
}
