package gg.modl.backend.migration.data;

import org.jspecify.annotations.Nullable;

public record ExportCounts(
    @Nullable Integer processed,
    @Nullable Integer total,
    @Nullable Integer failed,
    @Nullable Integer skipped
) {
}
