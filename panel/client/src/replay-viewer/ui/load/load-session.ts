import { apiFetch } from '@/lib/api';
import { AssetLoadError } from '../../assets/AssetLibrary';
import type { LoadedAssets } from '../../assets/loadAssets';
import { loadPreparedReplay } from '../../engine/loadPreparedReplay';
import type { PreparedReplay } from '../../engine/PreparedReplay';
import { type MinecraftVersion, minecraftVersion } from '../../format/MinecraftVersion';
import { readStream } from '../../format/readStream';
import { MAX_COMPRESSED_BYTES } from '../../format/ReplayLimits';
import { ReplayFormatError } from '../../format/ReplayModel';
import { loadAssets } from '../viewer-core';

export type LoadFailure = 'replay_missing' | 'replay_unreadable' | 'assets_unreachable' | 'unsupported_version' | 'assets_corrupt' | 'failed';

export interface LoadProgress {
  readonly loadedBytes: number;
  readonly totalBytes: number;
}

export interface LoadedSession {
  readonly replay: PreparedReplay;
  readonly assets: LoadedAssets;
}

class ReplayDownloadError extends Error {
  readonly status: number;

  constructor(status: number) {
    super(`Replay download failed with HTTP ${String(status)}`);
    this.name = 'ReplayDownloadError';
    this.status = status;
  }
}

const MISSING_STATUSES: ReadonlySet<number> = new Set([404, 410]);

export function loadFailureOf(error: unknown): LoadFailure | null {
  if (error instanceof DOMException && error.name === 'AbortError') return null;
  if (error instanceof AssetLoadError) {
    switch (error.kind) {
      case 'aborted':
        return null;
      case 'network':
        return 'assets_unreachable';
      case 'unsupported_version':
        return 'unsupported_version';
      case 'corrupt':
        return 'assets_corrupt';
    }
  }
  if (error instanceof ReplayFormatError) return 'replay_unreadable';
  if (error instanceof ReplayDownloadError) return MISSING_STATUSES.has(error.status) ? 'replay_missing' : 'failed';
  return 'failed';
}

export const LOAD_FAILURE_LABELS: Readonly<Record<LoadFailure, string>> = {
  replay_missing: 'replayViewer.failure.replayMissing',
  replay_unreadable: 'replayViewer.failure.replayUnreadable',
  assets_unreachable: 'replayViewer.failure.assetsUnreachable',
  unsupported_version: 'replayViewer.failure.unsupportedVersion',
  assets_corrupt: 'replayViewer.failure.assetsCorrupt',
  failed: 'replayViewer.failure.failed',
};

export function isRetryable(failure: LoadFailure): boolean {
  return failure === 'assets_unreachable' || failure === 'assets_corrupt' || failure === 'failed';
}

export type ReplayAccess = 'panel' | 'public';

async function requestReplay(url: string, access: ReplayAccess, signal: AbortSignal): Promise<Response> {
  switch (access) {
    case 'panel':
      return apiFetch(url, { signal, rateLimitFeedback: 'caller' });
    case 'public':
      return fetch(url, { signal, credentials: 'omit', referrerPolicy: 'no-referrer', redirect: 'error' });
  }
}

function declaredLength(response: Response): number {
  const declared = Number(response.headers.get('content-length') ?? '0');
  return Number.isSafeInteger(declared) && declared > 0 ? Math.min(declared, MAX_COMPRESSED_BYTES) : 0;
}

async function downloadReplay(source: SessionSource, signal: AbortSignal, onProgress: (loaded: number, total: number) => void): Promise<ArrayBuffer> {
  const response = await requestReplay(source.replayUrl, source.access, signal);
  if (!response.ok) throw new ReplayDownloadError(response.status);
  const body = response.body;
  if (body === null) throw new ReplayFormatError('replay download is empty');
  const declared = declaredLength(response);
  const bytes = await readStream(body, MAX_COMPRESSED_BYTES, () => new ReplayFormatError('replay file is too large'), (loaded) => {
    onProgress(loaded, Math.max(declared, loaded));
  });
  return bytes.buffer;
}

export interface SessionSource {
  readonly replayUrl: string;
  readonly access: ReplayAccess;
  readonly minecraftVersion: string;
}

export async function loadSession(source: SessionSource, outerSignal: AbortSignal, onProgress: (progress: LoadProgress) => void): Promise<LoadedSession> {
  const local = new AbortController();
  const signal = AbortSignal.any([outerSignal, local.signal]);
  let replayBytes = { loaded: 0, total: 0 };
  let assetBytes = { loaded: 0, total: 0 };
  const report = () => {
    onProgress({ loadedBytes: replayBytes.loaded + assetBytes.loaded, totalBytes: replayBytes.total + assetBytes.total });
  };
  const assetsFor = async (version: MinecraftVersion) => loadAssets(version, (loaded, total) => {
    assetBytes = { loaded, total };
    report();
  }, signal);
  const replayPromise = downloadReplay(source, signal, (loaded, total) => {
    replayBytes = { loaded, total };
    report();
  }).then(async (buffer) => loadPreparedReplay(buffer, signal));
  const announced = minecraftVersion(source.minecraftVersion);
  const earlyAssets = announced === null ? null : assetsFor(announced).catch((error: unknown) => {
    if (signal.aborted) throw error;
    return null;
  });
  let replay: PreparedReplay;
  let early: LoadedAssets | null;
  try {
    [replay, early] = await Promise.all([replayPromise, earlyAssets]);
  } catch (error) {
    local.abort();
    void earlyAssets?.then((library) => { library?.dispose(); }, () => undefined);
    throw error;
  }
  const recorded = minecraftVersion(replay.header.minecraftVersion);
  if (early?.version !== recorded?.id) early?.dispose();
  if (recorded === null) throw new AssetLoadError('unsupported_version', `unknown Minecraft version ${replay.header.minecraftVersion}`);
  const assets = early?.version === recorded.id ? early : await assetsFor(recorded);
  if (signal.aborted) {
    assets.dispose();
    signal.throwIfAborted();
  }
  return { replay, assets };
}
