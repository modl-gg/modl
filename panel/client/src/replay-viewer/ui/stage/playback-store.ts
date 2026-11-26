import { useMemo, useSyncExternalStore } from 'react';
import type { PlaybackState, ReplayPlayer, ReplayPlayerInfo } from '../../engine/ReplayPlayer';

export interface PlaybackSnapshot {
  readonly state: PlaybackState;
  readonly players: readonly ReplayPlayerInfo[];
}

export interface PlaybackStore {
  readonly subscribe: (onChange: () => void) => () => void;
  readonly snapshot: () => PlaybackSnapshot;
}

export function createPlaybackStore(player: ReplayPlayer): PlaybackStore {
  let current: PlaybackSnapshot = { state: player.state(), players: player.players() };
  const listeners = new Set<() => void>();
  let unsubscribe: (() => void) | null = null;

  const attach = () => {
    current = { state: player.state(), players: player.players() };
    unsubscribe = player.subscribe((state) => {
      current = { state, players: player.players() };
      for (const listener of listeners) listener();
    });
  };

  return {
    subscribe: (onChange) => {
      listeners.add(onChange);
      if (unsubscribe === null) attach();
      return () => {
        listeners.delete(onChange);
        if (listeners.size === 0 && unsubscribe !== null) {
          unsubscribe();
          unsubscribe = null;
        }
      };
    },
    snapshot: () => current,
  };
}

export function usePlayback(player: ReplayPlayer): PlaybackSnapshot {
  const store = useMemo(() => createPlaybackStore(player), [player]);
  return useSyncExternalStore(store.subscribe, store.snapshot);
}

export function hasEntity(info: ReplayPlayerInfo): boolean {
  return info.entityId >= 0;
}

export function viewedPlayer(snapshot: PlaybackSnapshot): ReplayPlayerInfo | null {
  const followed = snapshot.state.followedEntityId;
  return (followed === null || followed < 0 ? undefined : snapshot.players.find((info) => info.entityId === followed))
    ?? snapshot.players.find((info) => info.isTarget)
    ?? null;
}
