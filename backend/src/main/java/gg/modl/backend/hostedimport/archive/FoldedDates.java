package gg.modl.backend.hostedimport.archive;

import org.jspecify.annotations.Nullable;
import java.time.Instant;

public record FoldedDates(@Nullable Instant earliest, @Nullable Instant latest, long count) {
    static final FoldedDates EMPTY = new FoldedDates(null, null, 0);

    FoldedDates with(Instant date) {
        Instant first = earliest == null || date.isBefore(earliest) ? date : earliest;
        Instant last = latest == null || date.isAfter(latest) ? date : latest;
        return new FoldedDates(first, last, count + 1);
    }
}
