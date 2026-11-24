import { useCallback, useMemo } from 'react';
import { usePlayer, useLinkedAccounts, useLinkedAccountSearch } from '@/hooks/data/players';
import { usePlayerAllTickets } from '@/hooks/data/tickets';
import { usePunishmentTypes } from '@/hooks/data/punishments';
import { usePermissions } from '@/hooks/use-permissions';
import { buildPlayerProfile, type PlayerProfile } from './player-profile';
import { usePunishmentTypeCatalog } from '@/hooks/use-punishment-type-catalog';

const PLAYER_UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export type PlayerProfileView =
  | { readonly status: 'loaded'; readonly profile: PlayerProfile }
  | { readonly status: 'loading' | 'failed' | 'not-found'; readonly profile: null };

function validPlayerId(playerId: string): string {
  return PLAYER_UUID_PATTERN.test(playerId) ? playerId : '';
}

function profileViewOf(profile: PlayerProfile | null, query: { data: unknown; isLoading: boolean; isError: boolean }): PlayerProfileView {
  if (profile !== null) return { status: 'loaded', profile };
  if (query.isLoading) return { status: 'loading', profile: null };
  return { status: query.data === undefined && query.isError ? 'failed' : 'not-found', profile: null };
}

export function usePlayerProfile(requestedPlayerId: string) {
  const playerId = validPlayerId(requestedPlayerId);
  const { canReadPunishmentTypes, canModifyPunishments } = usePermissions();
  const playerQuery = usePlayer(playerId);
  const ticketsQuery = usePlayerAllTickets(playerId);
  const linkedAccountsQuery = useLinkedAccounts(playerId);
  const punishmentTypesQuery = usePunishmentTypes({ enabled: canReadPunishmentTypes });
  const player = playerQuery.data;
  const linkedAccountSearch = useLinkedAccountSearch(playerId, canModifyPunishments && (player ?? null) !== null);

  const { punishmentTypes, permittedTypesByCategory } = usePunishmentTypeCatalog(punishmentTypesQuery.data);

  const linkedAccounts = linkedAccountsQuery.data?.linkedAccounts;
  const profile = useMemo(
    () => (player ? buildPlayerProfile(player, linkedAccounts ?? [], punishmentTypes) : null),
    [player, linkedAccounts, punishmentTypes],
  );

  const { refetch: refetchPlayer } = playerQuery;
  const { refetch: refetchLinkedAccounts } = linkedAccountsQuery;
  const { refetch: searchLinkedAccounts } = linkedAccountSearch;
  const refreshLinkedAccounts = useCallback(() => {
    if (canModifyPunishments) void searchLinkedAccounts();
    else void refetchLinkedAccounts();
  }, [canModifyPunishments, searchLinkedAccounts, refetchLinkedAccounts]);
  const retryLoad = useCallback(() => { void refetchPlayer(); }, [refetchPlayer]);
  const refresh = useCallback(() => {
    retryLoad();
    refreshLinkedAccounts();
  }, [retryLoad, refreshLinkedAccounts]);

  return {
    ...profileViewOf(profile, playerQuery),
    playerId,
    playerQuery,
    refresh,
    retryLoad,
    tickets: ticketsQuery.data ?? [],
    isLoadingTickets: ticketsQuery.isLoading,
    isLoadingLinkedAccounts: linkedAccountsQuery.isLoading,
    isSearchingLinkedAccounts: linkedAccountSearch.isFetching,
    linkedAccountSearchFailed: linkedAccountSearch.isError,
    refreshLinkedAccounts,
    punishmentTypes,
    permittedTypesByCategory,
    isLoadingPunishmentTypes: punishmentTypesQuery.isLoading,
  };
}

export type PlayerProfileState = ReturnType<typeof usePlayerProfile>;
