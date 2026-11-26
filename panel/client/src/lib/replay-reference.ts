const URL_SYNTAX = /[/?#]/;
const REPLAY_LINK_PATH = '/replay';
const REPLAY_LINK_PARAM = 'id';

export const REPLAY_SEARCH_PARAM = 'replay';

export function replayIdFromReference(reference: string | null | undefined, origin: string): string | null {
  const trimmed = reference?.trim();
  if (!trimmed) return null;
  if (!URL_SYNTAX.test(trimmed)) return trimmed;

  try {
    const url = new URL(trimmed, origin);
    return url.pathname === REPLAY_LINK_PATH ? url.searchParams.get(REPLAY_LINK_PARAM)?.trim() || null : null;
  } catch {
    return null;
  }
}

export function replayIdFromLink(link: string, origin: string): string | null {
  try {
    const url = new URL(link.trim(), origin);
    if (url.origin !== new URL(origin).origin || url.pathname !== REPLAY_LINK_PATH) return null;
    return url.searchParams.get(REPLAY_LINK_PARAM)?.trim() || null;
  } catch {
    return null;
  }
}

export function withReplayParam(search: string, replayId: string): string {
  const params = new URLSearchParams(search);
  params.set(REPLAY_SEARCH_PARAM, replayId);
  return params.toString();
}

export function withoutReplayParam(search: string): string {
  const params = new URLSearchParams(search);
  params.delete(REPLAY_SEARCH_PARAM);
  return params.toString();
}

export function panelReplayPath(replayId: string): string {
  return `/panel?${withReplayParam('', replayId)}`;
}
