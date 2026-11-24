package gg.modl.backend.update.install;

import gg.modl.backend.update.ReleaseBuild;
import gg.modl.update.ArtifactKind;
import gg.modl.update.HostDescriptor;
import gg.modl.update.UpdateFiles;
import java.util.Optional;

final class RunningArtifact {
    private RunningArtifact() {
    }

    static Optional<String> of(UpdateFiles files, ReleaseBuild build, HostDescriptor host, ArtifactKind kind) {
        return build.version().equals(host.version())
            ? Optional.empty()
            : Optional.of(files.relativeArtifact(build.version(), kind));
    }
}
