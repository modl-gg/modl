package gg.modl.backend.update.api;

import gg.modl.backend.update.Deployment;
import gg.modl.backend.update.check.CheckOutcome;
import gg.modl.backend.update.compat.RefusedServers.RefusedServer;
import gg.modl.backend.update.install.InstallState;
import gg.modl.backend.update.install.Installability;
import gg.modl.update.FailedUpdate;
import gg.modl.update.ReleaseVersion;
import gg.modl.update.SignedRelease;
import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.List;

public record UpdateStatus(
    ReleaseVersion currentVersion,
    Deployment deployment,
    @Nullable OfferedUpdate release,
    boolean dismissed,
    boolean checksEnabled,
    boolean checksManagedByConfig,
    boolean includePrereleases,
    @Nullable Instant lastCheckedAt,
    @Nullable Instant nextCheckAt,
    @Nullable CheckOutcome lastOutcome,
    InstallState install,
    @Nullable FailedUpdate lastFailure,
    List<RefusedServer> refusedServers,
    boolean preUpdateBackup
) {
    public record OfferedUpdate(SignedRelease release, String releaseUrl, Installability availability, boolean requiresPluginUpdate,
                                String imageReference) {
    }
}
