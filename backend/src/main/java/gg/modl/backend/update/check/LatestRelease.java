package gg.modl.backend.update.check;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.update.Deployment;
import gg.modl.backend.update.DeploymentDetector;
import gg.modl.backend.update.ReleaseBuild;
import gg.modl.backend.update.UpdateProperties;
import gg.modl.backend.update.state.InstanceStateRepository;
import gg.modl.update.SignedRelease;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LatestRelease {
    private final InstanceStateRepository repository;
    private final UnitOfWork unitOfWork;
    private final OfferedRelease offered;
    private final ReleaseBuild build;
    private final UpdateProperties properties;
    private final DeploymentDetector deployment;

    public Optional<PluginReleaseNotice> pluginNotice() {
        if (deployment.deployment() == Deployment.EMBEDDED) {
            return Optional.empty();
        }
        return unitOfWork.read(repository::find)
            .flatMap(state -> offered.newerThan(build.version(), state))
            .map(SignedRelease::version)
            .map(Object::toString)
            .map(version -> new PluginReleaseNotice(version, properties.releasePage(version)));
    }
}
