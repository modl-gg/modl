package gg.modl.backend.update.state;

import org.jspecify.annotations.Nullable;
import java.time.Instant;

public record InstanceState(
    String lastRunVersion,
    Instant versionChangedAt,
    boolean checksEnabled,
    boolean includePrereleases,
    @Nullable Instant lastCheckAt,
    @Nullable String lastCheckOutcome,
    int checkFailures,
    @Nullable Instant nextCheckAt,
    ReleaseChannelState stable,
    ReleaseChannelState prerelease,
    @Nullable String notifiedVersion,
    @Nullable String dismissedVersion,
    @Nullable String dismissedBy
) {
    public boolean dismissed(String version, @Nullable String ownerEmail) {
        return version.equals(dismissedVersion) && ownerEmail != null && ownerEmail.equalsIgnoreCase(dismissedBy);
    }
}
