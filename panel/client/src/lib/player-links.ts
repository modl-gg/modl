export const LOOKUP_PATH = '/panel/lookup';
export const PLAYER_PAGE_PREFIX = '/panel/player/';
export const PLAYER_PARAM = 'player';
export const PUNISHMENT_PARAM = 'punishment';

export function playerPagePath(playerId: string, punishmentId = ''): string {
  const path = `${PLAYER_PAGE_PREFIX}${encodeURIComponent(playerId)}`;
  return punishmentId === '' ? path : `${path}?${new URLSearchParams({ [PUNISHMENT_PARAM]: punishmentId }).toString()}`;
}

export type PlayerOpenTarget = { readonly kind: 'window' } | { readonly kind: 'page'; readonly path: string };

const WINDOW_TARGET: PlayerOpenTarget = { kind: 'window' };

export function playerOpenTarget(isMobile: boolean, playerId: string, punishmentId = ''): PlayerOpenTarget {
  return isMobile ? { kind: 'page', path: playerPagePath(playerId, punishmentId) } : WINDOW_TARGET;
}

export interface PlayerUrlRequest {
  readonly playerId: string;
  readonly punishmentId: string;
}

export function playerUrlRequestOf(search: string): PlayerUrlRequest | null {
  const params = new URLSearchParams(search);
  const playerId = params.get(PLAYER_PARAM)?.trim() ?? '';
  if (playerId === '') return null;
  return { playerId, punishmentId: params.get(PUNISHMENT_PARAM)?.trim() ?? '' };
}

export interface PunishmentFocus {
  readonly punishmentId: string;
  readonly request: number;
}

export function pagePunishmentFocus(punishmentId: string): PunishmentFocus | null {
  return punishmentId === '' ? null : { punishmentId, request: 0 };
}

export function focusRequestFor(focus: PunishmentFocus | null, punishmentId: string): number | null {
  return focus !== null && focus.punishmentId === punishmentId ? focus.request : null;
}

export function showPlayerParam(params: URLSearchParams, playerId: string | null): boolean {
  if (playerId === null) {
    if (!params.has(PLAYER_PARAM)) return false;
    params.delete(PLAYER_PARAM);
    params.delete(PUNISHMENT_PARAM);
    return true;
  }
  if (params.get(PLAYER_PARAM) === playerId && !params.has(PUNISHMENT_PARAM)) return false;
  params.set(PLAYER_PARAM, playerId);
  params.delete(PUNISHMENT_PARAM);
  return true;
}
