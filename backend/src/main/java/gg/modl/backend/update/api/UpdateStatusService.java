package gg.modl.backend.update.api;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.update.DeploymentDetector;
import gg.modl.backend.update.ReleaseBuild;
import gg.modl.backend.update.UpdateErrors;
import gg.modl.backend.update.UpdateProperties;
import gg.modl.backend.update.api.UpdateStatus.OfferedUpdate;
import gg.modl.backend.update.check.CheckOutcome;
import gg.modl.backend.update.check.CheckPolicy;
import gg.modl.backend.update.check.DockerCommands;
import gg.modl.backend.update.check.OfferedRelease;
import gg.modl.backend.update.check.ReleaseChecker;
import gg.modl.backend.update.compat.CompatibilityOverview;
import gg.modl.backend.update.install.InstallCommand;
import gg.modl.backend.update.install.Installability;
import gg.modl.backend.update.install.UpdateInstaller;
import gg.modl.backend.update.state.InstanceState;
import gg.modl.backend.update.state.InstanceStateRepository;
import gg.modl.update.FailedUpdate;
import gg.modl.update.ReleaseVersion;
import gg.modl.update.SignedRelease;
import gg.modl.update.UpdateFiles;
import gg.modl.update.UpdateStateException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class UpdateStatusService {
    private final InstanceStateRepository repository;
    private final UnitOfWork unitOfWork;
    private final ReleaseChecker checker;
    private final CheckPolicy policy;
    private final OfferedRelease offered;
    private final UpdateInstaller installer;
    private final UpdateFiles files;
    private final UpdateProperties properties;
    private final DeploymentDetector deployment;
    private final ReleaseBuild build;
    private final InstanceContext instance;
    private final CompatibilityOverview compatibility;
    private final PanelActionAuditor auditor;

    public UpdateStatus status() {
        InstanceState state = currentState();
        Optional<SignedRelease> release = offered.newerThan(build.version(), state);
        boolean dismissed = release.isPresent() && state.dismissed(release.get().version().toString(), ownerEmail());
        return new UpdateStatus(
            build.version(),
            deployment.deployment(),
            release.map(this::describe).orElse(null),
            dismissed,
            policy.effectiveChecksEnabled(state) && policy.checksAvailable(),
            policy.settingsManagedByConfig() || !policy.checksAvailable(),
            state.includePrereleases(),
            state.lastCheckAt(),
            policy.automaticChecks(state) ? state.nextCheckAt() : null,
            state.lastCheckOutcome() == null ? null : CheckOutcome.fromStored(state.lastCheckOutcome()),
            installer.state(),
            lastFailure(),
            compatibility.refusedServers(),
            properties.backsUpBeforeUpdating());
    }

    public UpdateStatus checkNow() {
        checker.checkNow();
        return status();
    }

    public UpdateStatus dismiss(String version, String email) {
        requireOffered(version);
        unitOfWork.write(() -> repository.dismiss(version, email));
        return status();
    }

    public UpdateStatus saveSettings(boolean checksEnabled, boolean includePrereleases, String email) {
        InstanceState before = currentState();
        boolean storedChecks = (policy.settingsManagedByConfig() || !policy.checksAvailable()) ? before.checksEnabled() : checksEnabled;
        unitOfWork.write(() -> repository.updateSettings(storedChecks, includePrereleases));
        auditor.recordStaffAction(instance.instance(), email, "Changed update settings: automatic checks "
            + onOff(before.checksEnabled()) + " to " + onOff(storedChecks) + ", pre-releases " + onOff(before.includePrereleases())
            + " to " + onOff(includePrereleases));
        return status();
    }

    public UpdateStatus install(String version, boolean acknowledgePluginUpdate, String email) {
        SignedRelease release = requireOffered(version);
        if (installer.state().busy()) {
            throw new ConflictException("An update is already being installed", UpdateErrors.IN_PROGRESS);
        }
        if (installer.availability(release) != Installability.AVAILABLE) {
            throw new ConflictException("modl " + version + " cannot be installed from the panel here", UpdateErrors.NOT_INSTALLABLE);
        }
        if (!release.version().sameLine(build.version()) && !acknowledgePluginUpdate) {
            throw new ConflictException("modl " + version + " needs the plugin on every server updated; confirm it first",
                UpdateErrors.PLUGIN_ACK_REQUIRED);
        }
        installer.install(new InstallCommand(release, email));
        return status();
    }

    private SignedRelease requireOffered(String version) {
        return offered.newerThan(build.version(), currentState())
            .filter(release -> release.version().toString().equals(version))
            .orElseThrow(() -> new ConflictException("modl " + version + " is not the release this server offers", UpdateErrors.VERSION_STALE));
    }

    private OfferedUpdate describe(SignedRelease release) {
        ReleaseVersion version = release.version();
        return new OfferedUpdate(release, properties.releasePage(version.toString()), installer.availability(release),
            !version.sameLine(build.version()), DockerCommands.imageReference(release));
    }

    private InstanceState currentState() {
        return unitOfWork.read(repository::find).orElseThrow(() -> new IllegalStateException("The instance state row is missing"));
    }

    @Nullable
    private String ownerEmail() {
        Server server = instance.instance();
        return server.getAdminEmail();
    }

    @Nullable
    private FailedUpdate lastFailure() {
        try {
            return files.readLastFailure();
        } catch (UpdateStateException e) {
            log.warn("Could not read the last update failure: {}", e.getMessage());
            return null;
        }
    }

    private static String onOff(boolean value) {
        return value ? "on" : "off";
    }
}
