import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { create } from '@bufbuild/protobuf';
import { ServerCompatibility } from '@proto/modl/v1/servers_pb.ts';
import {
  DeploymentKind,
  DismissUpdateRequestSchema,
  InstallAvailability,
  InstallFailure,
  InstallPhase,
  InstallUpdateRequestSchema,
  UpdateCheckOutcome,
  UpdateSettingsRequestSchema,
  UpdateStatusResponseSchema,
  type AvailableRelease,
  type RefusedPluginServer,
  type UpdateStatusResponse,
} from '@proto/modl/v1/updates_pb.ts';
import { protoFetch, protoSend } from '@/lib/proto-fetch';
import { tsToIso } from '@/lib/proto-ui';
import type { QueryGate } from './query-gate';

const UPDATES_PATH = '/v1/panel/updates';
const IDLE_REFRESH_MS = 5 * 60 * 1000;
const INSTALLING_REFRESH_MS = 2_000;

export type UpdateDeployment = 'jar' | 'embedded' | 'docker' | 'unmanaged';
export type UpdateAvailability =
  | 'available'
  | 'docker'
  | 'unmanaged'
  | 'pluginJarRequired'
  | 'upgradePath'
  | 'java'
  | 'launcher'
  | 'dataDirectory';
export type UpdateInstallStage = 'idle' | 'downloading' | 'preparing' | 'restarting' | 'failed';
export type UpdateInstallProblem =
  | 'download'
  | 'verification'
  | 'diskSpace'
  | 'backupDirectory'
  | 'database'
  | 'preparation'
  | 'internal';
export type UpdateCheckResult =
  | 'succeeded'
  | 'noRelease'
  | 'networkError'
  | 'rateLimited'
  | 'invalidSignature'
  | 'invalidManifest'
  | 'unsupportedFormat';
export type RefusedCompatibility = 'pluginOlder' | 'serverOlder' | 'unknown';

export interface AvailableUpdate {
  version: string;
  prerelease: boolean;
  critical: boolean;
  publishedAt: string | null;
  releaseUrl: string;
  requiresPluginUpdate: boolean;
  availability: UpdateAvailability;
  imageReference: string;
  minUpgradeFrom: string;
  minJava: number;
}

export interface RefusedServer {
  platform: string;
  pluginVersion: string;
  compatibility: RefusedCompatibility;
  refusedAt: string | null;
}

export interface UpdateStatus {
  currentVersion: string;
  deployment: UpdateDeployment;
  release: AvailableUpdate | null;
  dismissed: boolean;
  checksEnabled: boolean;
  checksManagedByConfig: boolean;
  includePrereleases: boolean;
  lastCheckedAt: string | null;
  nextCheckAt: string | null;
  lastOutcome: UpdateCheckResult | null;
  install: {
    stage: UpdateInstallStage;
    targetVersion: string | null;
    problem: UpdateInstallProblem | null;
    downloadPercent: number;
  };
  lastFailure: { fromVersion: string; toVersion: string; failedAt: string | null; databaseRestored: boolean } | null;
  incompatibleServers: number;
  preUpdateBackup: boolean;
  refusedServers: RefusedServer[];
}

function deployment(kind: DeploymentKind): UpdateDeployment {
  switch (kind) {
    case DeploymentKind.JAR:
      return 'jar';
    case DeploymentKind.EMBEDDED:
      return 'embedded';
    case DeploymentKind.DOCKER:
      return 'docker';
    case DeploymentKind.UNMANAGED:
    case DeploymentKind.UNSPECIFIED:
      return 'unmanaged';
  }
}

function availability(value: InstallAvailability): UpdateAvailability {
  switch (value) {
    case InstallAvailability.AVAILABLE:
      return 'available';
    case InstallAvailability.DOCKER:
      return 'docker';
    case InstallAvailability.PLUGIN_JAR_REQUIRED:
      return 'pluginJarRequired';
    case InstallAvailability.UPGRADE_PATH_UNSUPPORTED:
      return 'upgradePath';
    case InstallAvailability.JAVA_TOO_OLD:
      return 'java';
    case InstallAvailability.LAUNCHER_TOO_OLD:
      return 'launcher';
    case InstallAvailability.DATA_DIRECTORY_UNSUPPORTED:
      return 'dataDirectory';
    case InstallAvailability.UNMANAGED:
    case InstallAvailability.UNSPECIFIED:
      return 'unmanaged';
  }
}

function stage(phase: InstallPhase): UpdateInstallStage {
  switch (phase) {
    case InstallPhase.DOWNLOADING:
      return 'downloading';
    case InstallPhase.PREPARING:
      return 'preparing';
    case InstallPhase.RESTARTING:
      return 'restarting';
    case InstallPhase.FAILED:
      return 'failed';
    case InstallPhase.IDLE:
    case InstallPhase.UNSPECIFIED:
      return 'idle';
  }
}

function problem(failure: InstallFailure): UpdateInstallProblem | null {
  switch (failure) {
    case InstallFailure.DOWNLOAD:
      return 'download';
    case InstallFailure.VERIFICATION:
      return 'verification';
    case InstallFailure.DISK_SPACE:
      return 'diskSpace';
    case InstallFailure.BACKUP_DIRECTORY:
      return 'backupDirectory';
    case InstallFailure.DATABASE:
      return 'database';
    case InstallFailure.PREPARATION:
      return 'preparation';
    case InstallFailure.INTERNAL:
      return 'internal';
    case InstallFailure.UNSPECIFIED:
      return null;
  }
}

function outcome(value: UpdateCheckOutcome): UpdateCheckResult | null {
  switch (value) {
    case UpdateCheckOutcome.SUCCEEDED:
      return 'succeeded';
    case UpdateCheckOutcome.NO_RELEASE:
      return 'noRelease';
    case UpdateCheckOutcome.NETWORK_ERROR:
      return 'networkError';
    case UpdateCheckOutcome.RATE_LIMITED:
      return 'rateLimited';
    case UpdateCheckOutcome.INVALID_SIGNATURE:
      return 'invalidSignature';
    case UpdateCheckOutcome.INVALID_MANIFEST:
      return 'invalidManifest';
    case UpdateCheckOutcome.UNSUPPORTED_FORMAT:
      return 'unsupportedFormat';
    case UpdateCheckOutcome.UNSPECIFIED:
      return null;
  }
}

function compatibility(value: ServerCompatibility): RefusedCompatibility {
  switch (value) {
    case ServerCompatibility.PLUGIN_OLDER:
      return 'pluginOlder';
    case ServerCompatibility.SERVER_OLDER:
      return 'serverOlder';
    case ServerCompatibility.COMPATIBLE:
    case ServerCompatibility.UNKNOWN:
    case ServerCompatibility.UNSPECIFIED:
      return 'unknown';
  }
}

function toRelease(release: AvailableRelease): AvailableUpdate {
  return {
    version: release.version,
    prerelease: release.prerelease,
    critical: release.critical,
    publishedAt: tsToIso(release.publishedAt),
    releaseUrl: release.releaseUrl,
    requiresPluginUpdate: release.requiresPluginUpdate,
    availability: availability(release.installAvailability),
    imageReference: release.imageReference,
    minUpgradeFrom: release.minUpgradeFrom,
    minJava: release.minJava,
  };
}

function toRefused(server: RefusedPluginServer): RefusedServer {
  return {
    platform: server.platform,
    pluginVersion: server.pluginVersion,
    compatibility: compatibility(server.compatibility),
    refusedAt: tsToIso(server.refusedAt),
  };
}

export function toUpdateStatus(response: UpdateStatusResponse): UpdateStatus {
  return {
    currentVersion: response.currentVersion,
    deployment: deployment(response.deployment),
    release: response.release ? toRelease(response.release) : null,
    dismissed: response.dismissed,
    checksEnabled: response.settings?.checksEnabled ?? false,
    checksManagedByConfig: response.settings?.checksManagedByConfig ?? false,
    includePrereleases: response.settings?.includePrereleases ?? false,
    lastCheckedAt: tsToIso(response.lastCheckedAt),
    nextCheckAt: tsToIso(response.nextCheckAt),
    lastOutcome: outcome(response.lastOutcome),
    install: {
      stage: stage(response.install?.phase ?? InstallPhase.UNSPECIFIED),
      targetVersion: response.install?.targetVersion ?? null,
      problem: problem(response.install?.failure ?? InstallFailure.UNSPECIFIED),
      downloadPercent: response.install?.downloadPercent ?? 0,
    },
    lastFailure: response.lastFailure
      ? {
          fromVersion: response.lastFailure.fromVersion,
          toVersion: response.lastFailure.toVersion,
          failedAt: tsToIso(response.lastFailure.failedAt),
          databaseRestored: response.lastFailure.databaseRestored,
        }
      : null,
    incompatibleServers: response.incompatibleServers,
    preUpdateBackup: response.preUpdateBackup,
    refusedServers: response.refusedServers.map(toRefused),
  };
}

export function isInstalling(status: UpdateStatus | undefined): boolean {
  const current = status?.install.stage;
  return current === 'downloading' || current === 'preparing' || current === 'restarting';
}

export function useUpdateStatus({ enabled = true }: QueryGate = {}) {
  return useQuery({
    queryKey: [UPDATES_PATH],
    enabled,
    queryFn: async () => toUpdateStatus(await protoFetch(UpdateStatusResponseSchema, UPDATES_PATH)),
    refetchInterval: (query) => (isInstalling(query.state.data) ? INSTALLING_REFRESH_MS : IDLE_REFRESH_MS),
    refetchIntervalInBackground: false,
    retry: false,
  });
}

function useUpdatesMutation<T>(send: (input: T) => Promise<UpdateStatusResponse>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: T) => toUpdateStatus(await send(input)),
    onSuccess: (status) => {
      queryClient.setQueryData([UPDATES_PATH], status);
    },
  });
}

export function useCheckForUpdates() {
  return useUpdatesMutation(async () =>
    protoFetch(UpdateStatusResponseSchema, `${UPDATES_PATH}/check`, { method: 'POST' }),
  );
}

export function useDismissUpdate() {
  return useUpdatesMutation(async (version: string) =>
    protoSend('POST', `${UPDATES_PATH}/dismiss`, DismissUpdateRequestSchema, create(DismissUpdateRequestSchema, { version }),
      UpdateStatusResponseSchema),
  );
}

export function useSaveUpdateSettings() {
  return useUpdatesMutation(async (settings: { checksEnabled: boolean; includePrereleases: boolean }) =>
    protoSend('PUT', `${UPDATES_PATH}/settings`, UpdateSettingsRequestSchema, create(UpdateSettingsRequestSchema, settings),
      UpdateStatusResponseSchema),
  );
}

export function useInstallUpdate() {
  return useUpdatesMutation(async (request: { version: string; acknowledgePluginUpdate: boolean }) =>
    protoSend('POST', `${UPDATES_PATH}/install`, InstallUpdateRequestSchema, create(InstallUpdateRequestSchema, request),
      UpdateStatusResponseSchema),
  );
}
