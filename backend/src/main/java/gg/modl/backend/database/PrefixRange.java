package gg.modl.backend.database;

import java.util.Optional;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.impl.DSL;

public final class PrefixRange {
    private static final int SURROGATE_START = 0xD800;
    private static final int SURROGATE_END = 0xDFFF;

    private PrefixRange() {
    }

    public static Condition of(Field<String> field, String prefix) {
        if (prefix.isEmpty()) {
            return DSL.trueCondition();
        }
        Condition lower = field.ge(prefix);
        return upperBound(prefix).map(upper -> lower.and(field.lt(upper))).orElse(lower);
    }

    static Optional<String> upperBound(String prefix) {
        int[] codePoints = prefix.codePoints().toArray();
        for (int index = codePoints.length - 1; index >= 0; index--) {
            int next = successor(codePoints[index]);
            if (next <= Character.MAX_CODE_POINT) {
                codePoints[index] = next;
                return Optional.of(new String(codePoints, 0, index + 1));
            }
        }
        return Optional.empty();
    }

    private static int successor(int codePoint) {
        int next = codePoint + 1;
        return next >= SURROGATE_START && next <= SURROGATE_END ? SURROGATE_END + 1 : next;
    }
}
