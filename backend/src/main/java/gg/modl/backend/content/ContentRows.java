package gg.modl.backend.content;

import gg.modl.backend.database.InClauseChunks;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.impl.DSL;

public final class ContentRows {
    private ContentRows() {
    }

    public static <T> void putIfPresent(Map<Field<?>, Object> changes, Field<T> field, @Nullable T value) {
        if (value != null) {
            changes.put(field, value);
        }
    }

    public static <R extends Record> void renumber(DSLContext dsl, Table<R> table, TableField<R, String> id,
                                                   TableField<R, Integer> ordinal, List<String> ids, Condition scope) {
        Map<String, Integer> positions = new TreeMap<>();
        for (int position = 0; position < ids.size(); position++) {
            positions.put(ids.get(position), position);
        }
        for (List<String> chunk : InClauseChunks.<String>of(positions.keySet())) {
            Map<String, Integer> assigned = new LinkedHashMap<>();
            chunk.forEach(key -> assigned.put(key, positions.get(key)));
            dsl.update(table)
                .set(ordinal, DSL.case_(id).mapValues(assigned).otherwise(ordinal))
                .where(id.in(chunk).and(scope))
                .execute();
        }
    }
}
