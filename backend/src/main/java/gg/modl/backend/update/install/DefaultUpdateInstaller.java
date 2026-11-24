package gg.modl.backend.update.install;

import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.update.Deployment;
import gg.modl.backend.update.DeploymentDetector;
import gg.modl.backend.update.JacksonJsonMaps;
import gg.modl.backend.update.ReleaseBuild;
import gg.modl.backend.update.UpdateErrors;
import gg.modl.backend.update.UpdateProperties;
import gg.modl.backend.update.launcher.UpdateLauncher;
import gg.modl.update.ArtifactMismatchException;
import gg.modl.update.EmbeddedUpdateContract;
import gg.modl.update.HostDescriptor;
import gg.modl.update.ReleaseArtifact;
import gg.modl.update.ReleaseKeys;
import gg.modl.update.ReleaseManifest;
import gg.modl.update.ReleaseVerificationException;
import gg.modl.update.ReleaseVersion;
import gg.modl.update.SignedRelease;
import gg.modl.update.UpdateFiles;
import gg.modl.update.UpdateTransaction;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class DefaultUpdateInstaller implements UpdateInstaller {
    static final Duration RESTART_DELAY = Duration.ofSeconds(2);

    private final UpdateHosts hosts;
    private final DeploymentDetector deployment;
    private final ReleaseBuild build;
    private final ReleaseKeys keys;
    private final UpdateFiles files;
    private final UpdateProperties properties;
    private final InstallPreflight preflight;
    private final ArtifactDownloader downloader;
    private final PanelActionAuditor auditor;
    private final InstanceContext instance;
    private final DataDirectoryProperties dataDirectory;
    private final Clock clock;
    private final InstallProgress progress = new InstallProgress();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "modl-update-installer");
        thread.setDaemon(true);
        return thread;
    });

    public DefaultUpdateInstaller(UpdateHosts hosts, DeploymentDetector deployment, ReleaseBuild build, ReleaseKeys keys,
                                  UpdateFiles files, UpdateProperties properties, InstallPreflight preflight,
                                  ArtifactDownloader downloader, PanelActionAuditor auditor, InstanceContext instance,
                                  DataDirectoryProperties dataDirectory, Clock clock) {
        this.hosts = hosts;
        this.deployment = deployment;
        this.build = build;
        this.keys = keys;
        this.files = files;
        this.properties = properties;
        this.preflight = preflight;
        this.downloader = downloader;
        this.auditor = auditor;
        this.instance = instance;
        this.dataDirectory = dataDirectory;
        this.clock = clock;
    }

    @Override
    public InstallState state() {
        return progress.current();
    }

    @Override
    public Installability availability(SignedRelease release) {
        if (deployment.deployment() == Deployment.DOCKER) {
            return Installability.DOCKER;
        }
        if (deployment.deployment() == Deployment.UNMANAGED) {
            return Installability.UNMANAGED;
        }
        Optional<UpdateHost> host = hosts.host();
        if (host.isEmpty()) {
            return Installability.LAUNCHER_TOO_OLD;
        }
        if (deployment.deployment() == Deployment.JAR && !UpdateLauncher.launcherDataDirectory().equals(dataDirectory.root())) {
            return Installability.DATA_DIRECTORY_UNSUPPORTED;
        }
        ReleaseManifest manifest = release.manifest();
        if (build.version().isOlderThan(manifest.minUpgradeFrom())) {
            return Installability.UPGRADE_PATH_UNSUPPORTED;
        }
        if (Runtime.version().feature() < manifest.minJava()) {
            return Installability.JAVA_TOO_OLD;
        }
        if (!hostAccepts(host.get(), release)) {
            return Installability.LAUNCHER_TOO_OLD;
        }
        if (host.get().sameLineOnly() && !release.version().sameLine(build.version())) {
            return Installability.PLUGIN_JAR_REQUIRED;
        }
        return Installability.AVAILABLE;
    }

    private boolean hostAccepts(UpdateHost host, SignedRelease release) {
        int expectedApi = deployment.deployment() == Deployment.EMBEDDED
            ? EmbeddedUpdateContract.HOST_API
            : EmbeddedUpdateContract.LAUNCHER_API;
        Optional<HostDescriptor> descriptor = host.descriptor();
        return host.hostApi(release) == expectedApi && descriptor.isPresent() && descriptor.get().api() == expectedApi
            && descriptor.get().knowsKey(release.keyId()) && keys.knows(release.keyId());
    }

    @Override
    public void install(InstallCommand command) {
        if (hasPendingUpdate() || !progress.begin(command.release().version().toString())) {
            throw new ConflictException("An update is already being installed", UpdateErrors.IN_PROGRESS);
        }
        worker.execute(() -> run(command));
    }

    private boolean hasPendingUpdate() {
        try {
            return files.readState().pending() != null;
        } catch (IOException e) {
            return true;
        }
    }

    private void run(InstallCommand command) {
        SignedRelease release = command.release();
        try {
            UpdateHost host = hosts.host().orElseThrow(() -> new InstallFailedException(InstallProblem.INTERNAL,
                "This deployment cannot install updates"));
            ReleaseArtifact artifact = release.manifest().artifact(host.artifactKind());
            preflight.check(files, artifact.size(), properties.backsUpBeforeUpdating());
            reverify(release);
            download(release, artifact);
            progress.stage(InstallStage.PREPARING);
            prepare(host, release);
            auditor.recordStaffAction(instance.instance(), command.requestedBy(), "Update from modl " + build.version() + " to "
                + release.version() + " started by " + command.requestedBy());
            files.writePending(staged(host, release));
            progress.stage(InstallStage.RESTARTING);
            Thread.sleep(RESTART_DELAY);
            host.restart();
        } catch (InstallFailedException e) {
            log.warn("The update to modl {} failed: {}", release.version(), e.getMessage(), e);
            progress.failed(e.problem());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            progress.failed(InstallProblem.INTERNAL);
        } catch (IOException | RuntimeException e) {
            log.warn("The update to modl {} failed", release.version(), e);
            progress.failed(InstallProblem.INTERNAL);
        }
    }

    private void reverify(SignedRelease release) throws InstallFailedException {
        try {
            SignedRelease.verify(release.envelope(), keys, JacksonJsonMaps.INSTANCE);
        } catch (ReleaseVerificationException e) {
            throw new InstallFailedException(InstallProblem.VERIFICATION, e.getMessage(), e);
        }
    }

    private void download(SignedRelease release, ReleaseArtifact artifact) throws InstallFailedException {
        try {
            downloader.download(files, release, artifact, progress::downloaded);
        } catch (ArtifactMismatchException e) {
            throw new InstallFailedException(InstallProblem.VERIFICATION, e.getMessage(), e);
        } catch (IOException e) {
            throw new InstallFailedException(InstallProblem.DOWNLOAD, e.getMessage(), e);
        }
    }

    private static void prepare(UpdateHost host, SignedRelease release) throws InstallFailedException {
        try {
            host.prepare(release);
        } catch (IOException e) {
            throw new InstallFailedException(InstallProblem.PREPARATION, e.getMessage(), e);
        }
    }

    private UpdateTransaction staged(UpdateHost host, SignedRelease release) {
        ReleaseVersion target = release.version();
        return UpdateTransaction.builder(UUID.randomUUID().toString(), host.transactionKind(), build.version(), target, clock.instant())
            .managed(true)
            .fromArtifact(host.runningArtifact().orElse(null))
            .toArtifact(files.relativeArtifact(target, host.artifactKind()))
            .build();
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }
}
