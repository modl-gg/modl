import { createContext, use, useCallback, useEffect, useMemo, useReducer, useRef, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { useLocation } from 'wouter';
import PlayerWindow from '@/components/windows/PlayerWindow';
import { WindowDock, WindowDockContext } from '@/components/layout/ResizableWindow';
import { useIsMobile } from '@/hooks/use-mobile';
import { playerOpenTarget, playerUrlRequestOf, showPlayerParam, type PunishmentFocus } from '@/lib/player-links';
import { dropWindow, EMPTY_WINDOW_STACK, raiseWindow, topWindow, windowLayer, type WindowStack } from '@/lib/window-stack';

interface PlayerWindowContextType {
  openPlayerWindow: (playerId: string, username?: string) => void;
  openPlayerPunishment: (playerId: string, punishmentId: string) => void;
}

export interface OpenPlayerWindow {
  readonly playerId: string;
  readonly username: string;
  readonly focus: PunishmentFocus | null;
}

interface PlayerWindowRequest {
  readonly playerId: string;
  readonly username: string;
  readonly punishmentId: string;
}

interface OpenPlayerWindows {
  windows: readonly OpenPlayerWindow[];
  stack: WindowStack;
  focusRequests: number;
}

type PlayerWindowAction =
  | { type: 'open'; request: PlayerWindowRequest }
  | { type: 'raise'; playerId: string }
  | { type: 'close'; playerId: string };

const NO_WINDOWS: OpenPlayerWindows = { windows: [], stack: EMPTY_WINDOW_STACK, focusRequests: 0 };

const PlayerWindowContext = createContext<PlayerWindowContextType | undefined>(undefined);

function requestedFocus(request: PlayerWindowRequest, focusRequest: number): PunishmentFocus | null {
  return request.punishmentId === '' ? null : { punishmentId: request.punishmentId, request: focusRequest };
}

function mergedWindow(current: OpenPlayerWindow, request: PlayerWindowRequest, focus: PunishmentFocus | null): OpenPlayerWindow {
  const username = request.username || current.username;
  if (username === current.username && focus === null && current.focus === null) return current;
  return { ...current, username, focus };
}

function openedWindows(windows: readonly OpenPlayerWindow[], request: PlayerWindowRequest, focus: PunishmentFocus | null): readonly OpenPlayerWindow[] {
  const current = windows.find((open) => open.playerId === request.playerId);
  if (current === undefined) return [...windows, { playerId: request.playerId, username: request.username, focus }];
  const merged = mergedWindow(current, request, focus);
  return merged === current ? windows : windows.map((open) => (open === current ? merged : open));
}

function playerWindowsReducer(state: OpenPlayerWindows, action: PlayerWindowAction): OpenPlayerWindows {
  switch (action.type) {
    case 'open': {
      const focusRequests = state.focusRequests + 1;
      const windows = openedWindows(state.windows, action.request, requestedFocus(action.request, focusRequests));
      const stack = raiseWindow(state.stack, action.request.playerId);
      if (windows === state.windows && stack === state.stack) return state;
      return { windows, stack, focusRequests };
    }
    case 'raise': {
      const stack = raiseWindow(state.stack, action.playerId);
      return stack === state.stack ? state : { ...state, stack };
    }
    case 'close':
      return {
        ...state,
        windows: state.windows.filter((open) => open.playerId !== action.playerId),
        stack: dropWindow(state.stack, action.playerId),
      };
  }
}

function useShownPlayerInAddress(stack: WindowStack): void {
  const shownPlayer = topWindow(stack);
  const appliedRef = useRef<string | null>(null);

  useEffect(() => {
    if (appliedRef.current === shownPlayer) return;
    appliedRef.current = shownPlayer;
    const url = new URL(window.location.href);
    if (!showPlayerParam(url.searchParams, shownPlayer)) return;
    window.history.replaceState(window.history.state, '', url.toString());
  }, [shownPlayer]);
}

export function PlayerWindowProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(playerWindowsReducer, NO_WINDOWS);
  const [, navigate] = useLocation();
  const isMobile = useIsMobile();
  const [dock, setDock] = useState<HTMLDivElement | null>(null);

  const open = useCallback((request: PlayerWindowRequest, replaceHistory: boolean) => {
    const target = playerOpenTarget(isMobile, request.playerId, request.punishmentId);
    if (target.kind === 'page') {
      navigate(target.path, { replace: replaceHistory });
      return;
    }
    dispatch({ type: 'open', request });
  }, [isMobile, navigate]);

  const openPlayerWindow = useCallback((playerId: string, username = '') => {
    open({ playerId, username, punishmentId: '' }, false);
  }, [open]);

  const openPlayerPunishment = useCallback((playerId: string, punishmentId: string) => {
    open({ playerId, username: '', punishmentId }, false);
  }, [open]);

  const restoredRef = useRef(false);
  useEffect(() => {
    if (restoredRef.current) return;
    restoredRef.current = true;
    const request = playerUrlRequestOf(window.location.search);
    if (request !== null) open({ playerId: request.playerId, username: '', punishmentId: request.punishmentId }, true);
  }, [open]);

  useShownPlayerInAddress(state.stack);

  const closePlayerWindow = useCallback((playerId: string) => {
    dispatch({ type: 'close', playerId });
  }, []);

  const raisePlayerWindow = useCallback((playerId: string) => {
    dispatch({ type: 'raise', playerId });
  }, []);

  const contextValue = useMemo<PlayerWindowContextType>(
    () => ({ openPlayerWindow, openPlayerPunishment }),
    [openPlayerWindow, openPlayerPunishment],
  );

  return (
    <PlayerWindowContext value={contextValue}>
      {children}
      {!isMobile && createPortal(
        <div data-player-windows className="relative z-45">
          <WindowDockContext value={dock}>
            {state.windows.map((playerWindow, index) => (
              <PlayerWindow
                key={playerWindow.playerId}
                playerWindow={playerWindow}
                layer={windowLayer(state.stack, playerWindow.playerId)}
                stagger={index}
                onActivate={raisePlayerWindow}
                onClose={closePlayerWindow}
              />
            ))}
          </WindowDockContext>
          <WindowDock onElement={setDock} />
        </div>,
        document.body,
      )}
    </PlayerWindowContext>
  );
}

export function usePlayerWindow() {
  const context = use(PlayerWindowContext);
  if (!context) {
    throw new Error('usePlayerWindow must be used within a PlayerWindowProvider');
  }
  return context;
}
