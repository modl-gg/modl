package gg.modl.backend.hostedimport.mapping;

import gg.modl.backend.database.TextLimits;
import gg.modl.backend.hostedimport.model.IssueCode;
import gg.modl.backend.hostedimport.model.IssueLog;
import org.jooq.TableField;
import org.jspecify.annotations.Nullable;

public final class ImportFit {
    public static final int MAX_TEXT_CODE_POINTS = 15 * 1024 * 1024;

    private final IssueLog issues;

    public ImportFit(IssueLog issues) {
        this.issues = issues;
    }

    public String column(TableField<?, String> field, String value) {
        String bounded = TextLimits.isIndexed(field) ? value.stripTrailing() : value;
        int limit = TextLimits.limit(field);
        return limit > 0 ? cut(bounded, limit) : cut(bounded, MAX_TEXT_CODE_POINTS);
    }

    public @Nullable String columnIfPresent(TableField<?, String> field, @Nullable String value) {
        return value == null ? null : column(field, value);
    }

    public String text(String value) {
        return cut(value, MAX_TEXT_CODE_POINTS);
    }

    public @Nullable String textIfPresent(@Nullable String value) {
        return value == null ? null : text(value);
    }

    public String cut(String value, int limit) {
        if (value.codePointCount(0, value.length()) <= limit) {
            return value;
        }
        issues.report(IssueCode.VALUE_TRUNCATED, "");
        return value.substring(0, value.offsetByCodePoints(0, limit));
    }

    public @Nullable String cutIfPresent(@Nullable String value, int limit) {
        return value == null ? null : cut(value, limit);
    }

    public IssueLog issues() {
        return issues;
    }
}
