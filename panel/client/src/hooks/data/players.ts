import { useCallback, useEffect } from 'react';
import { noop, queryOptions, useQuery, useMutation } from '@tanstack/react-query';
import { create, type JsonObject } from '@bufbuild/protobuf';
import { z } from 'zod';
import { queryClient } from '../../lib/queryClient';
import { useDebouncedValue } from '../use-debounced-value';
import { protoFetch, protoFetchOrNull, protoSend } from '@/lib/proto-fetch';
import { SimpleResponseSchema } from '@proto/modl/v1/common_pb.ts';
import { toNum } from '@/lib/proto-ui';
import { mapPunishment } from '@/lib/punishment-mapping';
import {
  CreatePlayerNoteRequestSchema,
  PlayerDetailResponseSchema,
  PanelLinkedAccountsResponseSchema,
  PanelFindAndLinkAccountsResponseSchema,
  PlayerReplaysResponseSchema,
  PlayerSearchResultsResponseSchema,
  VpnStatus,
  type IPEntry,
  type PlayerDetailResponse,
  type PlayerReplayResponse,
  type PlayerSearchResult,
} from '@proto/modl/v1/player_pb.ts';

export interface PlayerReplaySummary {
  replayId: string;
  targetUuid: string;
  targetName: string;
  mcVersion: string;
  fileSize: number;
  createdAt: string;
  status: string;
  replayUrl: string;
  matchSource: string;
}

export interface PlayerPresence {
  isOnline: boolean;
  lastSeen: string | null;
}

const presenceText = z.string().min(1).optional().catch(undefined);

const playerPresenceSchema = z.object({
  isOnline: z.boolean().catch(false),
  lastLogout: presenceText,
  lastDisconnect: presenceText,
});

function playerPresence(data: JsonObject | undefined): PlayerPresence {
  const parsed = playerPresenceSchema.safeParse(data ?? {});
  if (!parsed.success) return { isOnline: false, lastSeen: null };
  return {
    isOnline: parsed.data.isOnline,
    lastSeen: parsed.data.lastLogout ?? parsed.data.lastDisconnect ?? null,
  };
}

export type VpnStatusId = 'yes' | 'no' | 'unknown';

function vpnStatusOf(status: VpnStatus): VpnStatusId {
  switch (status) {
    case VpnStatus.YES:
      return 'yes';
    case VpnStatus.NO:
      return 'no';
    case VpnStatus.UNKNOWN:
    case VpnStatus.UNSPECIFIED:
      return 'unknown';
  }
}

const mapIpEntry = (entry: IPEntry) => ({
  ...entry,
  loginCount: toNum(entry.loginCount),
  vpnStatus: vpnStatusOf(entry.vpnStatus),
});

const mapPlayerDetail = (player: PlayerDetailResponse) => ({
  ...player,
  ipAddresses: player.ipAddresses.map(mapIpEntry),
  latestIPData: player.latestIpData === undefined ? undefined : mapIpEntry(player.latestIpData),
  presence: playerPresence(player.data),
  punishments: player.punishments.map(mapPunishment),
});

export type PlayerDetail = ReturnType<typeof mapPlayerDetail>;

const mapReplay = (replay: PlayerReplayResponse): PlayerReplaySummary => ({
  replayId: replay.replayId,
  targetUuid: replay.targetUuid,
  targetName: replay.targetName,
  mcVersion: replay.mcVersion,
  fileSize: toNum(replay.fileSize),
  createdAt: new Date(toNum(replay.createdAt)).toISOString(),
  status: replay.status,
  replayUrl: replay.replayUrl,
  matchSource: replay.matchSource,
});

export function usePlayer(uuid: string) {
  return useQuery({
    queryKey: ['/v1/panel/players', uuid],
    queryFn: async () => {
      const response = await protoFetchOrNull(PlayerDetailResponseSchema, `/v1/panel/players/${encodeURIComponent(uuid)}`);
      return response ? mapPlayerDetail(response) : null;
    },
    enabled: !!uuid,
    staleTime: 30_000,
    refetchOnWindowFocus: true
  });
}

export function useLinkedAccounts(uuid: string) {
  return useQuery({
    queryKey: ['/v1/panel/players/linked', uuid],
    queryFn: async () => {
      const response = await protoFetchOrNull(
        PanelLinkedAccountsResponseSchema,
        `/v1/panel/players/${encodeURIComponent(uuid)}/linked`,
      );
      return { linkedAccounts: response?.linkedAccounts ?? [] };
    },
    enabled: !!uuid,
    staleTime: 30_000,
    refetchOnWindowFocus: true
  });
}

function linkedAccountSearchQuery(minecraftUuid: string) {
  return queryOptions({
    queryKey: ['/v1/panel/players/find-linked', minecraftUuid],
    queryFn: async () => {
      const result = await protoFetch(
        PanelFindAndLinkAccountsResponseSchema,
        `/v1/panel/players/${encodeURIComponent(minecraftUuid)}/find-linked`,
        { method: 'POST' },
      );
      void queryClient.invalidateQueries({ queryKey: ['/v1/panel/players/linked', minecraftUuid] });
      void queryClient.invalidateQueries({ queryKey: ['/v1/panel/players', minecraftUuid], exact: true });
      return result;
    },
    staleTime: Infinity,
    gcTime: Infinity,
  });
}

export function useLinkedAccountSearch(minecraftUuid: string, searchOnce: boolean) {
  const search = useQuery({ ...linkedAccountSearchQuery(minecraftUuid), enabled: false });
  useEffect(() => {
    if (searchOnce && minecraftUuid !== '') queryClient.query(linkedAccountSearchQuery(minecraftUuid)).catch(noop);
  }, [searchOnce, minecraftUuid]);
  return search;
}

export function useAddPlayerNote() {
  return useMutation({
    mutationFn: async ({ uuid, text }: { uuid: string; text: string }) => {
      const request = create(CreatePlayerNoteRequestSchema, { text });
      return protoSend('POST', `/v1/panel/players/${encodeURIComponent(uuid)}/notes`, CreatePlayerNoteRequestSchema, request, SimpleResponseSchema);
    },
    onSuccess: (_data, variables) => {
      void queryClient.invalidateQueries({ queryKey: ['/v1/panel/players', variables.uuid] });
    }
  });
}

export function usePlayerReplays(uuid: string) {
  return useQuery<PlayerReplaySummary[]>({
    queryKey: ['/v1/panel/players', uuid, 'replays'],
    queryFn: async () => {
      const response = await protoFetchOrNull(PlayerReplaysResponseSchema, `/v1/panel/players/${encodeURIComponent(uuid)}/replays`);
      return response ? response.items.map(mapReplay) : [];
    },
    enabled: !!uuid,
    staleTime: 30000,
    refetchOnWindowFocus: true
  });
}

const PLAYER_SEARCH_MIN_LENGTH = 2;
const PLAYER_SEARCH_DEBOUNCE_MS = 300;

export type PlayerSearch =
  | { readonly state: 'inactive' }
  | { readonly state: 'pending' }
  | { readonly state: 'failed'; readonly retry: () => void }
  | { readonly state: 'settled'; readonly players: readonly PlayerSearchResult[] };

const NO_PLAYERS: readonly PlayerSearchResult[] = [];
const INACTIVE_PLAYER_SEARCH: PlayerSearch = { state: 'inactive' };
const PENDING_PLAYER_SEARCH: PlayerSearch = { state: 'pending' };

export function usePlayerSearch(searchQuery: string): PlayerSearch {
  const trimmedQuery = searchQuery.trim();
  const debouncedQuery = useDebouncedValue(trimmedQuery, PLAYER_SEARCH_DEBOUNCE_MS);
  const active = trimmedQuery.length >= PLAYER_SEARCH_MIN_LENGTH;
  const search = useQuery({
    queryKey: ['player-search', debouncedQuery],
    queryFn: async ({ signal }) => {
      const response = await protoFetch(
        PlayerSearchResultsResponseSchema,
        `/v1/panel/players?search=${encodeURIComponent(debouncedQuery)}`,
        { signal },
      );
      return response.items;
    },
    enabled: active && debouncedQuery.length >= PLAYER_SEARCH_MIN_LENGTH,
    staleTime: 1000 * 60,
  });
  const { refetch } = search;
  const retry = useCallback(() => { void refetch(); }, [refetch]);

  if (!active) return INACTIVE_PLAYER_SEARCH;
  if (debouncedQuery !== trimmedQuery) return PENDING_PLAYER_SEARCH;
  if (search.isError && search.data === undefined) return { state: 'failed', retry };
  if (search.isPending) return PENDING_PLAYER_SEARCH;
  return { state: 'settled', players: search.data };
}

export function foundPlayers(search: PlayerSearch): readonly PlayerSearchResult[] {
  return search.state === 'settled' ? search.players : NO_PLAYERS;
}
