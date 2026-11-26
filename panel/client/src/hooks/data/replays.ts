import { skipToken, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { PanelReplayResponseSchema, type PanelReplayResponse } from '@proto/modl/v1/replay_pb.ts';
import { protoFetchOrNull } from '@/lib/proto-fetch';
import { toNum } from '@/lib/proto-ui';
import { submitReplayLabels, type ReplayLabelInput } from '@/lib/replay-api';

export const REPLAY_COMPLETE_STATUS = 'COMPLETE';
const PROCESSING_POLL_MS = 5_000;

export interface ReplayMetadata {
  readonly replayId: string;
  readonly mcVersion: string;
  readonly status: string;
  readonly createdAt: number;
  readonly targetName: string;
  readonly labeled: boolean;
  readonly replayUrl: string;
}

function mapReplayMetadata(replay: PanelReplayResponse): ReplayMetadata {
  return {
    replayId: replay.replayId,
    mcVersion: replay.mcVersion,
    status: replay.status,
    createdAt: toNum(replay.createdAt),
    targetName: replay.targetName,
    labeled: replay.labeled,
    replayUrl: replay.replayUrl,
  };
}

export function replayMetadataQueryKey(replayId: string) {
  return ['/v1/panel/replays', replayId] as const;
}

export function useReplayMetadata(replayId: string) {
  return useQuery({
    queryKey: replayMetadataQueryKey(replayId),
    queryFn: replayId === ''
      ? skipToken
      : async () => {
        const replay = await protoFetchOrNull(PanelReplayResponseSchema, `/v1/panel/replays/${encodeURIComponent(replayId)}`);
        return replay === null ? null : mapReplayMetadata(replay);
      },
    retry: false,
    refetchOnWindowFocus: false,
    staleTime: 0,
    refetchInterval: (query) => {
      const replay = query.state.data;
      return replay !== undefined && replay !== null && replay.status !== REPLAY_COMPLETE_STATUS ? PROCESSING_POLL_MS : false;
    },
  });
}

export function useSubmitReplayLabels(replayId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (labels: readonly ReplayLabelInput[]) => submitReplayLabels(replayId, labels),
    onSuccess: () => {
      queryClient.setQueryData<ReplayMetadata | null>(replayMetadataQueryKey(replayId), (current) => (current ? { ...current, labeled: true } : current));
    },
  });
}
