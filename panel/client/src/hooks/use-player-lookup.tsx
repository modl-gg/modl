import { useQuery } from '@tanstack/react-query';
import { PlayerSearchResultsResponseSchema, type PlayerSearchResult } from '@proto/modl/v1/player_pb.ts';
import { PunishmentResponseSchema } from '@proto/modl/v1/punishment_pb.ts';
import { protoFetch } from '@/lib/proto-fetch';
import { toNum } from '@/lib/proto-ui';
import { useDebouncedValue } from './use-debounced-value';

const UUID_PATTERN = /^[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}$/i;

export function extractPlayerIdentifier(playerText: string): string {
  return playerText.replace(/\s*\([^)]*\).*$/, '').trim();
}

function selectBestPlayerMatch(players: PlayerSearchResult[], identifier: string): PlayerSearchResult | undefined {
  const normalizedIdentifier = identifier.trim().toLowerCase();
  const exactMatch = players.find((player) => player.username.trim().toLowerCase() === normalizedIdentifier);
  return exactMatch ?? players[0];
}

export function usePlayerLookup(identifier: string) {
  return useQuery({
    queryKey: ['player-lookup', identifier],
    queryFn: async () => {
      if (!identifier) throw new Error('No identifier provided');

      if (UUID_PATTERN.test(identifier)) {
        return { uuid: identifier, username: identifier };
      }

      const { items } = await protoFetch(
        PlayerSearchResultsResponseSchema,
        `/v1/panel/players?search=${encodeURIComponent(identifier)}`,
      );
      const player = selectBestPlayerMatch(items, identifier);
      if (!player) {
        throw new Error('Player not found');
      }
      if (!player.uuid) {
        throw new Error('Player lookup response missing UUID');
      }

      return {
        uuid: player.uuid,
        username: player.username || identifier,
      };
    },
    enabled: identifier.length > 0,
    staleTime: 1000 * 60 * 5,
    retry: false
  });
}

interface PunishmentLookupResult {
  playerUuid: string;
  playerUsername: string;
  punishment: {
    id: string;
    type: string;
    reason?: string | undefined;
    severity?: string | undefined;
    status?: string | undefined;
    issued: number;
    expiry?: number | undefined;
    active: boolean;
  };
}

export function usePunishmentLookup(punishmentId: string, debounceMs = 300) {
  const debouncedId = useDebouncedValue(punishmentId, debounceMs);

  return useQuery({
    queryKey: ['punishment-lookup', debouncedId],
    queryFn: async (): Promise<PunishmentLookupResult> => {
      if (!debouncedId) throw new Error('No punishment ID provided');

      const punishment = await protoFetch(
        PunishmentResponseSchema,
        `/v1/panel/players/punishments/${encodeURIComponent(debouncedId)}`,
      );
      return {
        playerUuid: punishment.playerUuid ?? '',
        playerUsername: punishment.playerUsername ?? '',
        punishment: {
          id: punishment.id,
          type: punishment.type,
          reason: punishment.reason,
          severity: punishment.severity,
          status: punishment.status,
          issued: toNum(punishment.issued),
          expiry: punishment.expires === undefined ? undefined : toNum(punishment.expires),
          active: punishment.active,
        },
      };
    },
    enabled: debouncedId.length > 0,
    staleTime: 1000 * 60 * 5,
    retry: false
  });
}
