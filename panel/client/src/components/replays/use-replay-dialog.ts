import { useCallback, useEffect, useRef } from 'react';
import { useLocation, useSearch } from 'wouter';
import { readSearchParam } from '@/hooks/use-search-param';
import { REPLAY_SEARCH_PARAM, withoutReplayParam, withReplayParam } from '@/lib/replay-reference';

const OPENED_IN_PANEL = 'modlReplayDialog';

function pathWithSearch(location: string, search: string): string {
  return search === '' ? location : `${location}?${search}`;
}

function openedInPanel(state: unknown): boolean {
  return typeof state === 'object' && state !== null && OPENED_IN_PANEL in state;
}

export function useOpenReplay(): (replayId: string) => void {
  const [location, navigate] = useLocation();
  const search = useSearch();
  return useCallback((replayId: string) => {
    if (readSearchParam(search, REPLAY_SEARCH_PARAM) === replayId) return;
    navigate(pathWithSearch(location, withReplayParam(search, replayId)), { state: { [OPENED_IN_PANEL]: true } });
  }, [location, navigate, search]);
}

export function useReplayDialogParam(): { readonly replayId: string; readonly close: () => void; readonly panelLink: string } {
  const [location, navigate] = useLocation();
  const search = useSearch();
  const replayId = readSearchParam(search, REPLAY_SEARCH_PARAM);
  const closedRef = useRef<string | null>(null);
  useEffect(() => {
    if (replayId === '') closedRef.current = null;
  }, [replayId]);
  const close = () => {
    if (closedRef.current === replayId) return;
    closedRef.current = replayId;
    if (openedInPanel(window.history.state)) {
      window.history.back();
      return;
    }
    navigate(pathWithSearch(location, withoutReplayParam(search)), { replace: true });
  };
  const panelLink = `${window.location.origin}${pathWithSearch(location, withReplayParam(withoutReplayParam(search), replayId))}`;
  return { replayId, close, panelLink };
}
