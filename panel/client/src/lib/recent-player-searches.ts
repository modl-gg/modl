import { z } from 'zod';
import type { PlayerSearchResult } from '@proto/modl/v1/player_pb.ts';
import { parseJsonOrNull } from '@/lib/http-error';

const STORAGE_KEY = 'recentPlayerSearches';
const MAX_STORED_SEARCHES = 10;
const ONLINE_STATUS = 'Online';

export interface RecentPlayer {
  uuid: string;
  username: string;
  isOnline: boolean;
}

export interface RecentPlayerSearch {
  player: RecentPlayer;
  timestamp: number;
}

const optionalText = z.string().optional().catch(undefined);
const optionalFlag = z.boolean().optional().catch(undefined);

const storedPlayerSchema = z.object({
  uuid: optionalText,
  minecraftUuid: optionalText,
  username: optionalText,
  status: optionalText,
  isOnline: optionalFlag,
  online: optionalFlag,
  data: z.object({ isOnline: optionalFlag }).optional().catch(undefined),
});

const storedSearchSchema = z.object({
  player: storedPlayerSchema,
  timestamp: z.number(),
});

type StoredSearch = z.infer<typeof storedSearchSchema>;

function normalizeStoredSearch({ player, timestamp }: StoredSearch): RecentPlayerSearch[] {
  const uuid = player.minecraftUuid || player.uuid || '';
  if (uuid === '') return [];
  const isOnline = player.status === ONLINE_STATUS
    || (player.isOnline ?? player.data?.isOnline ?? player.online ?? false);
  return [{ player: { uuid, username: player.username ?? '', isOnline }, timestamp }];
}

function byNewestFirst(a: RecentPlayerSearch, b: RecentPlayerSearch): number {
  return b.timestamp - a.timestamp;
}

export function readRecentPlayerSearches(): RecentPlayerSearch[] {
  let stored: string | null;
  try {
    stored = localStorage.getItem(STORAGE_KEY);
  } catch {
    return [];
  }
  if (stored === null) return [];
  const parsed = z.array(z.unknown()).safeParse(parseJsonOrNull(stored));
  if (!parsed.success) return [];
  return parsed.data
    .flatMap((entry) => {
      const search = storedSearchSchema.safeParse(entry);
      return search.success ? normalizeStoredSearch(search.data) : [];
    })
    .sort(byNewestFirst);
}

function writeRecentPlayerSearches(searches: RecentPlayerSearch[]): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(searches));
  } catch {
    return;
  }
}

export function recentPlayerOf(result: PlayerSearchResult): RecentPlayer {
  return {
    uuid: result.uuid,
    username: result.username,
    isOnline: result.isOnline || result.status === ONLINE_STATUS,
  };
}

export function withRecentPlayer(searches: RecentPlayerSearch[], player: RecentPlayer, timestamp: number): RecentPlayerSearch[] {
  const others = searches.filter((search) => search.player.uuid !== player.uuid);
  const next = [{ player, timestamp }, ...others].slice(0, MAX_STORED_SEARCHES);
  writeRecentPlayerSearches(next);
  return next;
}
