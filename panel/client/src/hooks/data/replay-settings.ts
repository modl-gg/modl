import { create } from '@bufbuild/protobuf';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ReplaySettingsEnvelopeSchema,
  ReplaySettingsSchema,
  UpdateReplaySettingsRequestSchema,
  type ReplaySettingsEnvelope,
} from '@proto/modl/v1/replay_pb.ts';
import { ApiHttpError, isConflictError } from '@/lib/http-error';
import { protoFetch, protoSend } from '@/lib/proto-fetch';
import { toNum } from '@/lib/proto-ui';
import type { WholeNumberRange } from '@/lib/whole-number';
import type { SettingsWrite } from './settings';
import { useConnectedServers } from './servers';
import type { QueryGate } from './query-gate';

const REPLAY_SETTINGS_PATH = '/v1/panel/settings/replays';
const HTTP_FORBIDDEN = 403;

export type ReplayNumberSetting = 'windowSeconds' | 'radiusBlocks' | 'memoryBudgetMb';

export const REPLAY_SETTING_RANGES: Readonly<Record<ReplayNumberSetting, WholeNumberRange>> = {
  windowSeconds: { min: 10, max: 300 },
  radiusBlocks: { min: 16, max: 128 },
  memoryBudgetMb: { min: 16, max: 1024 },
};

export const ANTICHEAT_NAME_MAX_LENGTH = 32;

export interface ReplaySettings {
  enabled: boolean;
  recordEveryPlayer: boolean;
  recordOnAnticheatFlag: boolean;
  anticheatIntegration: boolean;
  windowSeconds: number;
  radiusBlocks: number;
  memoryBudgetMb: number;
  anticheatName: string;
}

export interface VersionedReplaySettings {
  value: ReplaySettings;
  version: number;
  choiceMade: boolean;
}

export interface ReplayServerStatus {
  key: string;
  name: string;
  replaysSupported: boolean;
  replaysActive: boolean;
  anticheatIntegrationActive: boolean;
  detectedAnticheats: readonly string[];
  memoryBudgetMb: number | null;
}

function versionedOf(envelope: ReplaySettingsEnvelope): VersionedReplaySettings | null {
  const data = envelope.data;
  if (data === undefined) return null;
  return {
    value: {
      enabled: data.enabled,
      recordEveryPlayer: data.recordEveryPlayer,
      recordOnAnticheatFlag: data.recordOnAnticheatFlag,
      anticheatIntegration: data.anticheatIntegration,
      windowSeconds: data.windowSeconds,
      radiusBlocks: data.radiusBlocks,
      memoryBudgetMb: data.memoryBudgetMb,
      anticheatName: data.anticheatName,
    },
    version: envelope.meta === undefined ? 0 : toNum(envelope.meta.version),
    choiceMade: envelope.choiceMadeAt !== undefined,
  };
}

export function useReplaySettings({ enabled = true }: QueryGate = {}) {
  return useQuery({
    queryKey: [REPLAY_SETTINGS_PATH],
    enabled,
    queryFn: async () => versionedOf(await protoFetch(ReplaySettingsEnvelopeSchema, REPLAY_SETTINGS_PATH)),
    refetchOnWindowFocus: false,
  });
}

export function useSaveReplaySettings() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ expectedVersion, value }: SettingsWrite<ReplaySettings>): Promise<VersionedReplaySettings | null> => versionedOf(await protoSend(
      'PUT',
      REPLAY_SETTINGS_PATH,
      UpdateReplaySettingsRequestSchema,
      create(UpdateReplaySettingsRequestSchema, { expectedVersion: BigInt(expectedVersion), settings: create(ReplaySettingsSchema, value) }),
      ReplaySettingsEnvelopeSchema,
    )),
    onSuccess: (saved) => {
      queryClient.setQueryData([REPLAY_SETTINGS_PATH], saved);
    },
    onError: async (error) => {
      if (isConflictError(error) || (error instanceof ApiHttpError && error.status === HTTP_FORBIDDEN)) {
        await queryClient.invalidateQueries({ queryKey: [REPLAY_SETTINGS_PATH] });
      }
    },
  });
}

export function useReplayServerStatuses({ enabled = true }: QueryGate = {}) {
  const servers = useConnectedServers({ enabled });
  const statuses: ReplayServerStatus[] = (servers.data ?? []).flatMap((server) => {
    const features = server.features;
    if (features === null) return [];
    return [{
      key: server.instanceId || server.name,
      name: server.name,
      replaysSupported: features.replaysSupported,
      replaysActive: features.replaysActive,
      anticheatIntegrationActive: features.anticheatIntegrationActive,
      detectedAnticheats: features.detectedAnticheats,
      memoryBudgetMb: features.replayMemoryBudgetMb,
    }];
  });
  return { statuses, isPending: servers.isPending };
}
