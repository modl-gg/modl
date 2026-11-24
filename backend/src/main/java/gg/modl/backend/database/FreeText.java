package gg.modl.backend.database;

import org.jooq.Condition;
import org.jooq.Context;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.impl.CustomField;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

public final class FreeText {
    private static final String BINARY_COLLATION = "C";
    private static final String TERM = "search_term";

    private FreeText() {
    }

    public static Condition contains(Field<String> column, String term) {
        return column.containsIgnoreCase(termFoldedLikeItsColumn(term));
    }

    private static Field<String> termFoldedLikeItsColumn(String term) {
        return CustomField.of(TERM, SQLDataType.VARCHAR, context -> context.visit(boundTerm(context, term)));
    }

    private static Field<String> boundTerm(Context<?> context, String term) {
        Field<String> bound = DSL.val(term, SQLDataType.VARCHAR);
        return context.family() == SQLDialect.POSTGRES ? bound.collate(DSL.collation(BINARY_COLLATION)) : bound;
    }
}
