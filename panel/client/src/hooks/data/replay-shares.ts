import { create } from '@bufbuild/protobuf';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  CreateReplayShareRequestSchema,
  CreateReplayShareResponseSchema,
  ReplaySharesResponseSchema,
  ReplayShareExpiry,
  SharedReplayResponseSchema,
  type ReplayShare as ReplayShareMessage,
  type ReplaySharesResponse,
} from '@proto/modl/v1/replay_pb.ts';
import { protoFetch, protoFetchOrNull, protoSend } from '@/lib/proto-fetch';
import { toNum, tsToDate } from '@/lib/proto-ui';

export const SHARE_EXPIRY_CHOICES = ['one-day', 'seven-days', 'thirty-days', 'never'] as const;

export type ShareExpiryChoice = (typeof SHARE_EXPIRY_CHOICES)[number];

export const DEFAULT_SHARE_EXPIRY: ShareExpiryChoice = 'seven-days';

const EXPIRY_VALUES: Readonly<Record<ShareExpiryChoice, ReplayShareExpiry>> = {
  'one-day': ReplayShareExpiry.ONE_DAY,
  'seven-days': ReplayShareExpiry.SEVEN_DAYS,
  'thirty-days': ReplayShareExpiry.THIRTY_DAYS,
  never: ReplayShareExpiry.NEVER,
};

export interface ReplayShare {
  readonly id: string;
  readonly createdBy: string;
  readonly createdAt: number;
  readonly expiresAt: number | null;
  readonly revokedAt: number | null;
  readonly viewCount: number;
  readonly lastViewedAt: number | null;
}

export interface CreatedReplayShare {
  readonly share: ReplayShare;
  readonly url: string;
}

export interface SharedReplay {
  readonly targetName: string;
  readonly mcVersion: string;
  readonly createdAt: number | null;
  readonly fileUrl: string;
}

function epochOf(timestamp: Parameters<typeof tsToDate>[0]): number | null {
  return tsToDate(timestamp)?.getTime() ?? null;
}

function shareOf(share: ReplayShareMessage): ReplayShare {
  return {
    id: share.id,
    createdBy: share.createdBy,
    createdAt: epochOf(share.createdAt) ?? 0,
    expiresAt: epochOf(share.expiresAt),
    revokedAt: epochOf(share.revokedAt),
    viewCount: toNum(share.viewCount),
    lastViewedAt: epochOf(share.lastViewedAt),
  };
}

function sharesOf(response: ReplaySharesResponse): readonly ReplayShare[] {
  return response.shares.map(shareOf);
}

function sharesPath(replayId: string): string {
  return `/v1/panel/replays/${encodeURIComponent(replayId)}/shares`;
}

function sharesQueryKey(replayId: string) {
  return ['/v1/panel/replays', replayId, 'shares'] as const;
}

export function useReplayShares(replayId: string) {
  return useQuery({
    queryKey: sharesQueryKey(replayId),
    queryFn: async () => sharesOf(await protoFetch(ReplaySharesResponseSchema, sharesPath(replayId))),
    retry: false,
    refetchOnWindowFocus: false,
  });
}

export function useCreateReplayShare(replayId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (expiry: ShareExpiryChoice): Promise<CreatedReplayShare> => {
      const created = await protoSend(
        'POST',
        sharesPath(replayId),
        CreateReplayShareRequestSchema,
        create(CreateReplayShareRequestSchema, { expiry: EXPIRY_VALUES[expiry] }),
        CreateReplayShareResponseSchema,
        { rateLimitFeedback: 'caller' },
      );
      if (created.share === undefined) throw new Error('The share response carried no share');
      return { share: shareOf(created.share), url: created.url };
    },
    onSuccess: (created) => {
      queryClient.setQueryData<readonly ReplayShare[]>(sharesQueryKey(replayId), (current) => current && [created.share, ...current]);
      void queryClient.invalidateQueries({ queryKey: sharesQueryKey(replayId) });
    },
  });
}

export function useRevokeReplayShare(replayId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (shareId: string): Promise<readonly ReplayShare[]> => sharesOf(await protoFetch(
      ReplaySharesResponseSchema,
      `${sharesPath(replayId)}/${encodeURIComponent(shareId)}`,
      { method: 'DELETE' },
    )),
    onSuccess: (shares) => {
      queryClient.setQueryData(sharesQueryKey(replayId), shares);
    },
    onError: () => {
      void queryClient.invalidateQueries({ queryKey: sharesQueryKey(replayId) });
    },
  });
}

export function useSharedReplay(token: string) {
  return useQuery({
    queryKey: ['/v1/public/replays/shared', token],
    queryFn: async (): Promise<SharedReplay | null> => {
      const shared = await protoFetchOrNull(SharedReplayResponseSchema, `/v1/public/replays/shared/${encodeURIComponent(token)}`);
      if (shared === null) return null;
      return {
        targetName: shared.targetName,
        mcVersion: shared.mcVersion,
        createdAt: epochOf(shared.createdAt),
        fileUrl: shared.fileUrl,
      };
    },
    retry: false,
    refetchOnWindowFocus: false,
    staleTime: Infinity,
  });
}
