package gg.modl.backend.update.api;

import gg.modl.backend.infrastructure.proto.ProtoMapperSupport;
import gg.modl.backend.update.Deployment;
import gg.modl.backend.update.api.UpdateStatus.OfferedUpdate;
import gg.modl.backend.update.check.CheckOutcome;
import gg.modl.backend.update.install.InstallProblem;
import gg.modl.backend.update.install.InstallStage;
import gg.modl.backend.update.install.Installability;
import gg.modl.proto.modl.v1.AvailableRelease;
import gg.modl.proto.modl.v1.DeploymentKind;
import gg.modl.proto.modl.v1.FailedUpdate;
import gg.modl.proto.modl.v1.InstallAvailability;
import gg.modl.proto.modl.v1.InstallFailure;
import gg.modl.proto.modl.v1.InstallPhase;
import gg.modl.proto.modl.v1.RefusedPluginServer;
import gg.modl.proto.modl.v1.ServerCompatibility;
import gg.modl.proto.modl.v1.UpdateCheckOutcome;
import gg.modl.proto.modl.v1.UpdateInstallState;
import gg.modl.proto.modl.v1.UpdateSettings;
import gg.modl.proto.modl.v1.UpdateStatusResponse;
import gg.modl.update.ReleaseManifest;
import gg.modl.update.VersionCompatibility;
import org.jspecify.annotations.Nullable;

final class UpdateProtoMapper {
    private UpdateProtoMapper() {
    }

    static UpdateStatusResponse toResponse(UpdateStatus status) {
        UpdateStatusResponse.Builder response = UpdateStatusResponse.newBuilder()
            .setCurrentVersion(status.currentVersion().toString())
            .setDeployment(deployment(status.deployment()))
            .setDismissed(status.dismissed())
            .setSettings(UpdateSettings.newBuilder()
                .setChecksEnabled(status.checksEnabled())
                .setChecksManagedByConfig(status.checksManagedByConfig())
                .setIncludePrereleases(status.includePrereleases()))
            .setLastOutcome(outcome(status.lastOutcome()))
            .setInstall(install(status))
            .setIncompatibleServers(status.refusedServers().size())
            .setPreUpdateBackup(status.preUpdateBackup());
        if (status.release() != null) {
            response.setRelease(release(status.release()));
        }
        if (status.lastCheckedAt() != null) {
            response.setLastCheckedAt(ProtoMapperSupport.toTimestamp(status.lastCheckedAt().toEpochMilli()));
        }
        if (status.nextCheckAt() != null) {
            response.setNextCheckAt(ProtoMapperSupport.toTimestamp(status.nextCheckAt().toEpochMilli()));
        }
        if (status.lastFailure() != null) {
            response.setLastFailure(FailedUpdate.newBuilder()
                .setFromVersion(status.lastFailure().from().toString())
                .setToVersion(status.lastFailure().to().toString())
                .setFailedAt(ProtoMapperSupport.toTimestamp(status.lastFailure().failedAt().toEpochMilli()))
                .setDatabaseRestored(status.lastFailure().restored()));
        }
        status.refusedServers().forEach(refused -> response.addRefusedServers(RefusedPluginServer.newBuilder()
            .setPlatform(refused.platform())
            .setPluginVersion(refused.pluginVersion())
            .setCompatibility(compatibility(refused.compatibility()))
            .setRefusedAt(ProtoMapperSupport.toTimestamp(refused.refusedAt().toEpochMilli()))));
        return response.build();
    }

    private static ServerCompatibility compatibility(VersionCompatibility compatibility) {
        return switch (compatibility) {
            case COMPATIBLE -> ServerCompatibility.SERVER_COMPATIBILITY_COMPATIBLE;
            case PLUGIN_OLDER -> ServerCompatibility.SERVER_COMPATIBILITY_PLUGIN_OLDER;
            case SERVER_OLDER -> ServerCompatibility.SERVER_COMPATIBILITY_SERVER_OLDER;
            case UNKNOWN -> ServerCompatibility.SERVER_COMPATIBILITY_UNKNOWN;
        };
    }

    private static AvailableRelease release(OfferedUpdate offered) {
        ReleaseManifest manifest = offered.release().manifest();
        return AvailableRelease.newBuilder()
            .setVersion(manifest.version().toString())
            .setPrerelease(manifest.version().isPrerelease())
            .setCritical(manifest.critical())
            .setPublishedAt(ProtoMapperSupport.toTimestamp(manifest.publishedAt().toEpochMilli()))
            .setReleaseUrl(offered.releaseUrl())
            .setRequiresPluginUpdate(offered.requiresPluginUpdate())
            .setInstallAvailability(availability(offered.availability()))
            .setImageReference(offered.imageReference())
            .setMinUpgradeFrom(manifest.minUpgradeFrom().toString())
            .setMinJava(manifest.minJava())
            .build();
    }

    private static UpdateInstallState install(UpdateStatus status) {
        UpdateInstallState.Builder install = UpdateInstallState.newBuilder()
            .setPhase(phase(status.install().stage()))
            .setFailure(failure(status.install().problem()))
            .setDownloadPercent(status.install().downloadPercent());
        if (status.install().targetVersion() != null) {
            install.setTargetVersion(status.install().targetVersion());
        }
        return install.build();
    }

    private static DeploymentKind deployment(Deployment deployment) {
        return switch (deployment) {
            case JAR -> DeploymentKind.DEPLOYMENT_KIND_JAR;
            case EMBEDDED -> DeploymentKind.DEPLOYMENT_KIND_EMBEDDED;
            case DOCKER -> DeploymentKind.DEPLOYMENT_KIND_DOCKER;
            case UNMANAGED -> DeploymentKind.DEPLOYMENT_KIND_UNMANAGED;
        };
    }

    private static UpdateCheckOutcome outcome(@Nullable CheckOutcome outcome) {
        if (outcome == null) {
            return UpdateCheckOutcome.UPDATE_CHECK_OUTCOME_UNSPECIFIED;
        }
        return switch (outcome) {
            case SUCCEEDED -> UpdateCheckOutcome.UPDATE_CHECK_OUTCOME_SUCCEEDED;
            case NO_RELEASE -> UpdateCheckOutcome.UPDATE_CHECK_OUTCOME_NO_RELEASE;
            case NETWORK_ERROR -> UpdateCheckOutcome.UPDATE_CHECK_OUTCOME_NETWORK_ERROR;
            case RATE_LIMITED -> UpdateCheckOutcome.UPDATE_CHECK_OUTCOME_RATE_LIMITED;
            case INVALID_SIGNATURE -> UpdateCheckOutcome.UPDATE_CHECK_OUTCOME_INVALID_SIGNATURE;
            case INVALID_MANIFEST -> UpdateCheckOutcome.UPDATE_CHECK_OUTCOME_INVALID_MANIFEST;
            case UNSUPPORTED_FORMAT -> UpdateCheckOutcome.UPDATE_CHECK_OUTCOME_UNSUPPORTED_FORMAT;
        };
    }

    private static InstallAvailability availability(Installability availability) {
        return switch (availability) {
            case AVAILABLE -> InstallAvailability.INSTALL_AVAILABILITY_AVAILABLE;
            case DOCKER -> InstallAvailability.INSTALL_AVAILABILITY_DOCKER;
            case UNMANAGED -> InstallAvailability.INSTALL_AVAILABILITY_UNMANAGED;
            case PLUGIN_JAR_REQUIRED -> InstallAvailability.INSTALL_AVAILABILITY_PLUGIN_JAR_REQUIRED;
            case UPGRADE_PATH_UNSUPPORTED -> InstallAvailability.INSTALL_AVAILABILITY_UPGRADE_PATH_UNSUPPORTED;
            case JAVA_TOO_OLD -> InstallAvailability.INSTALL_AVAILABILITY_JAVA_TOO_OLD;
            case LAUNCHER_TOO_OLD -> InstallAvailability.INSTALL_AVAILABILITY_LAUNCHER_TOO_OLD;
            case DATA_DIRECTORY_UNSUPPORTED -> InstallAvailability.INSTALL_AVAILABILITY_DATA_DIRECTORY_UNSUPPORTED;
        };
    }

    private static InstallPhase phase(InstallStage stage) {
        return switch (stage) {
            case IDLE -> InstallPhase.INSTALL_PHASE_IDLE;
            case DOWNLOADING -> InstallPhase.INSTALL_PHASE_DOWNLOADING;
            case PREPARING -> InstallPhase.INSTALL_PHASE_PREPARING;
            case RESTARTING -> InstallPhase.INSTALL_PHASE_RESTARTING;
            case FAILED -> InstallPhase.INSTALL_PHASE_FAILED;
        };
    }

    private static InstallFailure failure(@Nullable InstallProblem problem) {
        if (problem == null) {
            return InstallFailure.INSTALL_FAILURE_UNSPECIFIED;
        }
        return switch (problem) {
            case DOWNLOAD -> InstallFailure.INSTALL_FAILURE_DOWNLOAD;
            case VERIFICATION -> InstallFailure.INSTALL_FAILURE_VERIFICATION;
            case DISK_SPACE -> InstallFailure.INSTALL_FAILURE_DISK_SPACE;
            case BACKUP_DIRECTORY -> InstallFailure.INSTALL_FAILURE_BACKUP_DIRECTORY;
            case DATABASE -> InstallFailure.INSTALL_FAILURE_DATABASE;
            case PREPARATION -> InstallFailure.INSTALL_FAILURE_PREPARATION;
            case INTERNAL -> InstallFailure.INSTALL_FAILURE_INTERNAL;
        };
    }
}
