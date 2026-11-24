package gg.modl.backend.database;

import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.jooq.Condition;
import org.jooq.TableField;
import org.jooq.impl.DSL;

public final class ColumnLookups {
    private ColumnLookups() {
    }

    public static Condition matches(TableField<?, String> field, @Nullable String value) {
        return value != null && TextLimits.canMatch(field, value) ? field.eq(TextLimits.require(field, value)) : DSL.falseCondition();
    }

    public static Condition differsFrom(TableField<?, String> field, @Nullable String value) {
        return value != null && TextLimits.canMatch(field, value) ? field.ne(TextLimits.require(field, value)) : DSL.trueCondition();
    }

    public static Condition matchesAny(TableField<?, String> field, Collection<String> values) {
        List<String> matchable = matchable(field, values);
        return matchable.isEmpty() ? DSL.falseCondition() : field.in(matchable);
    }

    public static List<String> matchable(TableField<?, String> field, Collection<String> values) {
        return values.stream()
            .filter(value -> TextLimits.canMatch(field, value))
            .map(value -> TextLimits.require(field, value))
            .distinct()
            .sorted()
            .toList();
    }
}
