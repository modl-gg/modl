import { useCallback, useSyncExternalStore } from 'react';

const TICK_MS = 1000;

function secondsUntil(deadline: number | null): number {
  return deadline === null ? 0 : Math.max(0, Math.ceil((deadline - Date.now()) / 1000));
}

export function useSecondsRemaining(deadline: number | null): number {
  const subscribe = useCallback((onTick: () => void) => {
    if (deadline === null) return () => undefined;

    const interval = setInterval(() => {
      onTick();
      if (secondsUntil(deadline) === 0) clearInterval(interval);
    }, TICK_MS);
    return () => { clearInterval(interval); };
  }, [deadline]);

  return useSyncExternalStore(subscribe, () => secondsUntil(deadline));
}

export function formatCountdown(totalSeconds: number): string {
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, '0')}`;
}
