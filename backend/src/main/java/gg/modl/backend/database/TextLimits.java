package gg.modl.backend.database;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.jooq.Index;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.UniqueKey;

public final class TextLimits {
    private static final char SPACE = ' ';
    private static final Map<TableField<?, String>, Boolean> INDEXED = new ConcurrentHashMap<>();

    private TextLimits() {
    }

    public static @Nullable String requireIfPresent(TableField<?, String> field, @Nullable String value) {
        return value == null ? null : require(field, value);
    }

    public static String require(TableField<?, String> field, String value) {
        StorableText.require(value);
        String bound = isIndexed(field) ? stripTrailingSpaces(value) : value;
        int limit = field.getDataType().length();
        if (exceeds(limit, bound)) {
            throw new ValueTooLongException(limit);
        }
        return bound;
    }

    public static int limit(TableField<?, String> field) {
        return field.getDataType().length();
    }

    public static boolean canMatch(TableField<?, String> field, @Nullable String value) {
        return value != null && StorableText.isStorable(value) && !exceeds(limit(field), value);
    }

    private static boolean exceeds(int limit, String value) {
        return limit > 0 && value.codePointCount(0, value.length()) > limit;
    }

    public static boolean isIndexed(TableField<?, String> field) {
        return INDEXED.computeIfAbsent(field, TextLimits::partOfAnyIndex);
    }

    private static boolean partOfAnyIndex(TableField<?, String> field) {
        Table<?> table = field.getTable();
        if (table == null) {
            return false;
        }
        Stream<String> indexColumns = table.getIndexes().stream()
            .flatMap(TextLimits::columnNames);
        Stream<String> keyColumns = table.getKeys().stream()
            .map(UniqueKey::getFields)
            .flatMap(fields -> fields.stream().map(TableField::getName));
        return Stream.concat(indexColumns, keyColumns).anyMatch(name -> Objects.equals(name, field.getName()));
    }

    private static Stream<String> columnNames(Index index) {
        return index.getFields().stream().map(sortField -> sortField.$field().getName());
    }

    private static String stripTrailingSpaces(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == SPACE) {
            end--;
        }
        return value.substring(0, end);
    }
}
