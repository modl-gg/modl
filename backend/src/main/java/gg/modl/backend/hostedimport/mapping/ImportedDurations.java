package gg.modl.backend.hostedimport.mapping;

import gg.modl.backend.hostedimport.model.IssueCode;
import gg.modl.backend.hostedimport.model.IssueLog;
import org.jspecify.annotations.Nullable;

final class ImportedDurations {
    private ImportedDurations() {
    }

    @Nullable
    static Long of(@Nullable Object value, IssueLog issues) {
        if (value instanceof Integer number) {
            return number.longValue();
        }
        if (value instanceof Long number) {
            return number;
        }
        if (value instanceof Double number) {
            if (Double.isNaN(number)) {
                return null;
            }
            if (number >= (double) Long.MAX_VALUE) {
                issues.report(IssueCode.PUNISHMENT_DURATION_SATURATED, "");
                return Long.MAX_VALUE;
            }
            return number <= (double) Long.MIN_VALUE ? -1L : number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
