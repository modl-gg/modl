package gg.modl.backend.replay.data;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Data
@NoArgsConstructor
public class ReplayDocument {
    private String id;
    private @Nullable String mcVersion;
    private @Nullable String targetUuid;
    private @Nullable String targetName;
    private long fileSize;
    private @Nullable Integer formatVersion;
    private @Nullable String storageKey;
    private String status;
    private Instant createdAt;
    private @Nullable List<ReplayLabel> labels;

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_COMPLETE = "COMPLETE";
    public static final String STATUS_FAILED = "FAILED";

    public Optional<String> completedStorageKey() {
        return STATUS_COMPLETE.equals(status) ? Optional.ofNullable(storageKey) : Optional.empty();
    }

    public boolean hasLabels() {
        return labels != null && !labels.isEmpty();
    }
}
