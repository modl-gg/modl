import { PublicReplayResponseSchema, type PublicReplayResponse } from '@proto/modl/v1/replay_pb.ts';
import { apiFetch } from './api';
import i18n from '@/lib/i18n';
import { protoFetchOrNull } from './proto-fetch';
import { toNum } from './proto-ui';

export interface ReplayMetadata {
  replayId: string;
  mcVersion: string;
  fileSize: number;
  timestamp: number;
  replayUrl: string;
  status: string;
  labeled: boolean;
}

export async function submitReplayLabels(replayId: string, labels: unknown[]): Promise<void> {
  const response = await apiFetch(`/v1/panel/replays/${encodeURIComponent(replayId)}/label`, {
    method: 'POST',
    body: { players: labels },
  });

  if (!response.ok) {
    throw new Error(`Failed to submit labels: ${response.status}`);
  }
}

function mapReplayMetadata(replay: PublicReplayResponse): ReplayMetadata {
  return {
    replayId: replay.replayId,
    mcVersion: replay.mcVersion,
    fileSize: toNum(replay.fileSize),
    timestamp: toNum(replay.timestamp),
    replayUrl: replay.replayUrl,
    status: replay.status,
    labeled: replay.labeled,
  };
}

export async function fetchReplayMetadata(replayId: string): Promise<ReplayMetadata> {
  const replay = await protoFetchOrNull(PublicReplayResponseSchema, `/v1/public/replays/${encodeURIComponent(replayId)}`);
  if (replay === null) throw new Error(i18n.t('pages.replay.notFound'));
  return mapReplayMetadata(replay);
}
