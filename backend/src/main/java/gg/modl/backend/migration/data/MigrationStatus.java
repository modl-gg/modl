package gg.modl.backend.migration.data;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

@Value
@Builder
public class MigrationStatus {
    String id;
    String type;
    ImportState status;
    @Nullable Integer recordsProcessed;
    @Nullable Integer recordsSkipped;
    @Nullable Integer totalRecords;
    @Nullable Integer exportFailed;
    @Nullable Integer exportSkipped;
    @Nullable String startedFrom;
    @Nullable String startedAddress;
    @Nullable ScopedPunishments scopedPunishments;
    @Nullable IpHistoryRetention ipHistory;
    @Nullable ImportEnding ending;
    @Nullable String error;
    @Nullable Instant startedAt;
    @Nullable Instant lastProgressAt;
    @Nullable Instant completedAt;

    public boolean hasGoneStale(Instant now) {
        if (!status.isActive()) {
            return false;
        }
        Instant lastActivity = lastProgressAt != null ? lastProgressAt : startedAt;
        return lastActivity == null || lastActivity.isBefore(status.staleBefore(now));
    }
}
