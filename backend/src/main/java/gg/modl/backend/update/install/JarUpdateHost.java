package gg.modl.backend.update.install;

import gg.modl.backend.update.ReleaseBuild;
import gg.modl.backend.update.launcher.UpdateLauncher;
import gg.modl.backend.update.launcher.UpdateRestart;
import gg.modl.update.ArtifactKind;
import gg.modl.update.EmbeddedUpdateContract;
import gg.modl.update.HostDescriptor;
import gg.modl.update.ReleaseKeys;
import gg.modl.update.SignedRelease;
import gg.modl.update.TransactionKind;
import gg.modl.update.UpdateFiles;
import java.util.Optional;
import org.springframework.context.ConfigurableApplicationContext;

public final class JarUpdateHost implements UpdateHost {
    private final ConfigurableApplicationContext context;
    private final UpdateFiles files;
    private final HostDescriptor descriptor;
    private final ReleaseBuild build;

    public JarUpdateHost(ConfigurableApplicationContext context, UpdateFiles files, ReleaseBuild build, ReleaseKeys keys) {
        this.context = context;
        this.files = files;
        this.build = build;
        String inherited = System.getenv(EmbeddedUpdateContract.LAUNCHER_ENVIRONMENT);
        HostDescriptor parent = HostDescriptor.parse(inherited);
        this.descriptor = parent != null ? parent : UpdateLauncher.descriptor(build.version(), keys);
    }

    @Override
    public ArtifactKind artifactKind() {
        return ArtifactKind.SERVER;
    }

    @Override
    public TransactionKind transactionKind() {
        return TransactionKind.JAR;
    }

    @Override
    public boolean sameLineOnly() {
        return false;
    }

    @Override
    public int hostApi(SignedRelease release) {
        return release.manifest().launcherApi();
    }

    @Override
    public Optional<HostDescriptor> descriptor() {
        return Optional.of(descriptor);
    }

    @Override
    public Optional<String> runningArtifact() {
        return RunningArtifact.of(files, build, descriptor, ArtifactKind.SERVER);
    }

    @Override
    public void prepare(SignedRelease release) {
    }

    @Override
    public void restart() {
        UpdateRestart.restartJarForUpdate(context);
    }
}
