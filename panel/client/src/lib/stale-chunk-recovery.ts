const RELOAD_MARK_KEY = 'modl:stale-chunk-reloaded';
export const STALE_CHUNK_RELOAD_WINDOW_MS = 30_000;

const CHUNK_LOAD_FAILURES: readonly RegExp[] = [
  /Failed to fetch dynamically imported module/i,
  /error loading dynamically imported module/i,
  /Importing a module script failed/i,
  /Unable to preload CSS/i,
  /Loading (?:CSS )?chunk \S+ failed/i,
];

export interface ReloadMarkStore {
  read: () => string | null;
  write: (value: string) => void;
}

export function isChunkLoadError(error: unknown): boolean {
  return error instanceof Error && CHUNK_LOAD_FAILURES.some((pattern) => pattern.test(error.message));
}

export function claimStaleChunkReload(store: ReloadMarkStore | null, nowMs: number): boolean {
  if (store === null) return false;
  const lastReloadAt = Number(store.read() ?? Number.NaN);
  if (Number.isFinite(lastReloadAt) && nowMs - lastReloadAt >= 0 && nowMs - lastReloadAt < STALE_CHUNK_RELOAD_WINDOW_MS) return false;
  store.write(String(nowMs));
  return true;
}

function sessionMarkStore(): ReloadMarkStore | null {
  try {
    const storage = window.sessionStorage;
    storage.getItem(RELOAD_MARK_KEY);
    return {
      read: () => storage.getItem(RELOAD_MARK_KEY),
      write: (value) => { storage.setItem(RELOAD_MARK_KEY, value); },
    };
  } catch {
    return null;
  }
}

let reloadRequested = false;

export function reloadForStaleChunk(): boolean {
  if (reloadRequested) return true;
  try {
    if (!claimStaleChunkReload(sessionMarkStore(), Date.now())) return false;
  } catch {
    return false;
  }
  reloadRequested = true;
  window.location.reload();
  return true;
}

export function registerStaleChunkRecovery(): void {
  window.addEventListener('vite:preloadError', (event) => {
    if (reloadForStaleChunk()) event.preventDefault();
  });
}
