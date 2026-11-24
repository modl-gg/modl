package gg.modl.backend.database.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.modl.backend.database.jooq.DefaultSchema;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.jooq.Check;
import org.jooq.DataType;
import org.jooq.Field;
import org.jooq.ForeignKey;
import org.jooq.Index;
import org.jooq.SQLDialect;
import org.jooq.SortField;
import org.jooq.SortOrder;
import org.jooq.Table;
import org.jooq.UniqueKey;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

class SchemaGoldenTest {
    private static final String GOLDEN_RESOURCE = "golden/sql-schema-model.txt";
    private static final Path ACTUAL = Path.of("build", "sql-schema-model.actual.txt");
    private static final String INDENT = "  ";

    @Test
    void theGeneratedSchemaModelMatchesTheGolden() {
        String actual = render();
        writeActualForInspection(actual);
        String expected = readGolden();

        assertEquals(expected, actual, () -> "The jOOQ schema model drifted from " + GOLDEN_RESOURCE + " at " + firstDifference(expected, actual)
            + "; compare with " + ACTUAL.toAbsolutePath() + " and update the fixture only together with a deliberate migration");
    }

    private static String render() {
        StringBuilder out = new StringBuilder();
        DefaultSchema.DEFAULT_SCHEMA.getTables().stream()
            .sorted(Comparator.comparing(Table::getName))
            .forEach(table -> renderTable(table, out));
        return out.toString();
    }

    private static void renderTable(Table<?> table, StringBuilder out) {
        out.append("table ").append(table.getName()).append('\n');
        for (Field<?> field : table.fields()) {
            out.append(INDENT).append("column ").append(column(field)).append('\n');
        }
        UniqueKey<?> primaryKey = table.getPrimaryKey();
        if (primaryKey != null) {
            out.append(INDENT).append("primary key ").append(primaryKey.getName()).append(' ').append(columns(primaryKey.getFields())).append('\n');
        }
        table.getKeys().stream()
            .filter(key -> !key.isPrimary())
            .sorted(Comparator.comparing(UniqueKey::getName))
            .forEach(key -> out.append(INDENT).append("unique key ").append(key.getName()).append(' ').append(columns(key.getFields())).append('\n'));
        table.getIndexes().stream()
            .sorted(Comparator.comparing(Index::getName))
            .forEach(index -> out.append(INDENT).append(index(index)).append('\n'));
        table.getReferences().stream()
            .sorted(Comparator.comparing(ForeignKey::getName))
            .forEach(key -> out.append(INDENT).append(foreignKey(key)).append('\n'));
        table.getChecks().stream()
            .map(check -> "check " + condition(check))
            .sorted()
            .forEach(check -> out.append(INDENT).append(check).append('\n'));
    }

    private static String column(Field<?> field) {
        DataType<?> type = field.getDataType();
        DataType<?> sqlType = type.getSQLDataType();
        StringBuilder column = new StringBuilder(field.getName()).append(' ').append(sqlType.getTypeName().toUpperCase(Locale.ROOT));
        if (type.length() > 0) {
            column.append('(').append(type.length()).append(')');
        }
        column.append(' ').append(field.getType().getSimpleName());
        column.append(type.nullable() ? " null" : " not null");
        if (type.defaulted()) {
            column.append(" default ").append(ModelSchema.renderedDefault(type));
        }
        return column.toString();
    }

    private static String index(Index index) {
        String columns = index.getFields().stream().map(SchemaGoldenTest::sortField).collect(Collectors.joining(", ", "(", ")"));
        return (index.getUnique() ? "unique index " : "index ") + index.getName() + ' ' + columns;
    }

    private static String sortField(SortField<?> field) {
        return field.$field().getName() + (field.getOrder() == SortOrder.DESC ? " DESC" : "");
    }

    private static String foreignKey(ForeignKey<?, ?> key) {
        return "foreign key " + key.getName() + ' ' + columns(key.getFields()) + " references " + key.getKey().getTable().getName()
            + ' ' + columns(key.getKeyFields()) + " on delete " + key.getDeleteRule();
    }

    private static String condition(Check<?> check) {
        return DSL.using(SQLDialect.SQLITE).render(check.condition());
    }

    private static String columns(List<? extends Field<?>> fields) {
        return String.join(", ", ModelSchema.names(fields)).transform(names -> "(" + names + ")");
    }

    private static String firstDifference(String expected, String actual) {
        List<String> expectedLines = expected.lines().toList();
        List<String> actualLines = actual.lines().toList();
        for (int line = 0; line < Math.max(expectedLines.size(), actualLines.size()); line++) {
            String wanted = line < expectedLines.size() ? expectedLines.get(line) : "<end of fixture>";
            String found = line < actualLines.size() ? actualLines.get(line) : "<end of model>";
            if (!wanted.equals(found)) {
                return "line " + (line + 1) + ": expected [" + wanted + "] but the model has [" + found + "]";
            }
        }
        return "the line endings";
    }

    private static void writeActualForInspection(String actual) {
        try {
            Files.createDirectories(ACTUAL.getParent());
            Files.writeString(ACTUAL, actual, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String readGolden() {
        try (InputStream stream = SchemaGoldenTest.class.getClassLoader().getResourceAsStream(GOLDEN_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing golden resource " + GOLDEN_RESOURCE + "; inspect " + ACTUAL + " to seed it");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
