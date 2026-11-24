package gg.modl.backend.support.sql;

import gg.modl.backend.database.jooq.DefaultSchema;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jooq.ForeignKey;
import org.jooq.Table;

public final class SchemaOrder {
    private SchemaOrder() {
    }

    public static List<Table<?>> parentsFirst() {
        List<Table<?>> tables = new ArrayList<>(DefaultSchema.DEFAULT_SCHEMA.getTables());
        tables.sort(Comparator.comparing(Table::getName));
        Set<Table<?>> ordered = new LinkedHashSet<>();
        for (Table<?> table : tables) {
            visit(table, ordered);
        }
        return List.copyOf(ordered);
    }

    public static List<Table<?>> childrenFirst() {
        List<Table<?>> tables = new ArrayList<>(parentsFirst());
        Collections.reverse(tables);
        return tables;
    }

    private static void visit(Table<?> table, Set<Table<?>> ordered) {
        if (ordered.contains(table)) {
            return;
        }
        for (ForeignKey<?, ?> reference : table.getReferences()) {
            Table<?> parent = reference.getKey().getTable();
            if (!parent.equals(table)) {
                visit(parent, ordered);
            }
        }
        ordered.add(table);
    }
}
