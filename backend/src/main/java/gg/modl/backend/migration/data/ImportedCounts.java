package gg.modl.backend.migration.data;

import org.jspecify.annotations.Nullable;

public record ImportedCounts(int processed, int skipped, @Nullable Integer total) {
    public static final ImportedCounts NONE = new ImportedCounts(0, 0, null);
}
