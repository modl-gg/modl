package gg.modl.backend.update.state;

import org.jspecify.annotations.Nullable;
import java.time.Instant;

public record CheckRecord(
    Instant checkedAt,
    String outcome,
    int failures,
    Instant nextCheckAt,
    ReleaseChannelState stable,
    ReleaseChannelState prerelease
) {
    public CheckRecord {
        if (failures < 0) {
            throw new IllegalArgumentException("failures must not be negative");
        }
    }

    public static CheckRecord of(Instant checkedAt, String outcome, int failures, Instant nextCheckAt,
                                 @Nullable ReleaseChannelState stable, @Nullable ReleaseChannelState prerelease) {
        return new CheckRecord(checkedAt, outcome, failures, nextCheckAt,
            stable == null ? ReleaseChannelState.EMPTY : stable, prerelease == null ? ReleaseChannelState.EMPTY : prerelease);
    }
}
