package gg.modl.backend.database.sql;

import gg.modl.backend.database.jooq.DefaultSchema;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.jooq.DataType;
import org.jooq.Field;
import org.jooq.ForeignKey;
import org.jooq.Index;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.impl.DSL;

final class ModelSchema {
    private ModelSchema() {
    }

    static Map<String, TableShape> tables() {
        Map<String, TableShape> tables = new TreeMap<>();
        for (Table<?> table : DefaultSchema.DEFAULT_SCHEMA.getTables()) {
            tables.put(table.getName(), shape(table));
        }
        return tables;
    }

    static List<String> names(List<? extends Field<?>> fields) {
        return fields.stream().<String>map(Field::getName).toList();
    }

    static String renderedDefault(DataType<?> type) {
        return DSL.using(SQLDialect.SQLITE).render(type.defaultValue());
    }

    private static TableShape shape(Table<?> table) {
        List<String> primaryKey = table.getPrimaryKey() == null ? List.of() : names(table.getPrimaryKey().getFields());
        return new TableShape(table.getName(),
            Arrays.stream(table.fields()).map(ModelSchema::column).toList(),
            primaryKey,
            table.getIndexes().stream().map(ModelSchema::index).collect(Collectors.toSet()),
            table.getReferences().stream().map(ModelSchema::foreignKey).collect(Collectors.toSet()));
    }

    private static TableShape.Column column(Field<?> field) {
        DataType<?> type = field.getDataType();
        String defaultValue = type.defaulted() ? ColumnTypes.normalizedDefault(renderedDefault(type), field.getType()) : null;
        return new TableShape.Column(field.getName(), field.getType(), type.nullable(), type.length(), defaultValue);
    }

    private static TableShape.Index index(Index index) {
        List<String> columns = index.getFields().stream().map(sortField -> sortField.$field().getName()).toList();
        return new TableShape.Index(index.getName(), columns, index.getUnique());
    }

    private static TableShape.ForeignKey foreignKey(ForeignKey<?, ?> foreignKey) {
        return new TableShape.ForeignKey(foreignKey.getName(), names(foreignKey.getFields()), foreignKey.getKey().getTable().getName(),
            names(foreignKey.getKeyFields()), foreignKey.getDeleteRule());
    }
}
