package gg.modl.backend.migration.data;

import gg.modl.backend.content.CappedText;
import gg.modl.backend.database.StorableText;
import org.jspecify.annotations.Nullable;
import java.time.Instant;

public record ImportEnd(ImportEnding ending, @Nullable String detail, Instant at) {
    private static final int MAX_DETAIL_LENGTH = 1000;

    public ImportEnd {
        detail = detail == null || detail.isBlank() ? null : CappedText.of(StorableText.strip(detail), MAX_DETAIL_LENGTH);
    }
}
