package gg.modl.backend.database.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.database.jooq.DefaultSchema;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jooq.Field;
import org.jooq.Table;
import org.junit.jupiter.api.Test;

class ColumnLimitCoverageTest {
    private static final List<JavaSource> SOURCES = JavaSource.under(Path.of("src", "main", "java")).stream()
        .filter(source -> !source.path().startsWith("gg/modl/backend/database/jooq/"))
        .toList();
    private static final Set<String> KEYS_COPIED_FROM_STORED_PARENTS = Set.of(
        "STAFF_ROLE_PERMISSIONS.ROLE_ID",
        "STAFF_TICKET_SUBSCRIPTIONS.STAFF_ID");
    private static final String GUARDS = String.join("|",
        "require", "requireIfPresent", "identityIfPresent", "fitIfPresent", "canMatch", "matches", "matchable", "matchesAny", "differsFrom", "matchesIdentity", "identity",
        "fit", "fitOrNull", "reference", "sortedValues", "valueOrUnchanged", "updateText", "storableKeys");

    @Test
    void everyBoundedColumnTheCodeUsesPassesATextGuard() {
        Set<String> unguarded = new TreeSet<>();
        for (Table<?> table : DefaultSchema.DEFAULT_SCHEMA.getTables()) {
            for (Field<?> field : table.fields()) {
                String column = javaName(table, field);
                if (isBoundedText(field) && !KEYS_COPIED_FROM_STORED_PARENTS.contains(column)
                    && isUsed(column) && !isGuarded(column)) {
                    unguarded.add(column);
                }
            }
        }

        assertEquals(Set.of(), unguarded);
    }

    @Test
    void anUnguardedKeyColumnIsAForeignKeyToAGuardedColumnOfTheSameLength() {
        Set<String> verified = new TreeSet<>();
        for (Table<?> table : DefaultSchema.DEFAULT_SCHEMA.getTables()) {
            table.getReferences().forEach(key -> {
                Field<?> child = key.getFields().getFirst();
                String column = javaName(table, child);
                if (KEYS_COPIED_FROM_STORED_PARENTS.contains(column)) {
                    Field<?> parent = key.getKeyFields().getFirst();
                    assertEquals(parent.getDataType().length(), child.getDataType().length(), column);
                    assertTrue(isGuarded(javaName(key.getKey().getTable(), parent)), column);
                    verified.add(column);
                }
            });
        }

        assertEquals(new TreeSet<>(KEYS_COPIED_FROM_STORED_PARENTS), verified);
    }

    private static boolean isBoundedText(Field<?> field) {
        return field.getType() == String.class && field.getDataType().length() > 0;
    }

    private static boolean isUsed(String column) {
        Pattern use = Pattern.compile("(?<![\\w])" + Pattern.quote(column) + "(?![\\w])");
        return SOURCES.stream().anyMatch(source -> use.matcher(source.code()).find());
    }

    private static boolean isGuarded(String column) {
        Pattern guard = Pattern.compile("(?<![\\w])(" + GUARDS + ")\\(\\s*(?:Tables\\.)?" + Pattern.quote(column) + "\\s*,");
        return SOURCES.stream().anyMatch(source -> guard.matcher(source.code()).find());
    }

    private static String javaName(Table<?> table, Field<?> field) {
        return (table.getName().substring(TableNamePrefixTest.PREFIX.length()) + "." + field.getName()).toUpperCase(Locale.ROOT);
    }
}
