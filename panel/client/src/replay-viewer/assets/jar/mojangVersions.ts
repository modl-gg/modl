import { AssetLoadError } from '../AssetLibrary.ts';
import { type JsonRecord, isRecord, parseJsonBytes } from '../json.ts';
import { readStream } from '../../format/readStream.ts';
import { sha1Hex } from './sha1.ts';

export const MANIFEST_URL = 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json';
const META_HOST = 'piston-meta.mojang.com';
const DATA_HOST = 'piston-data.mojang.com';
const LEGACY_DATA_HOST = 'launcher.mojang.com';
const SHA1 = /^[0-9a-f]{40}$/;
const OBJECT_PATH = /^\/v1\/(objects|packages)\/[0-9a-f]{40}\/[A-Za-z0-9._-]+$/;
const MAX_VERSION_JSON_BYTES = 2 * 1024 * 1024;
const MAX_MANIFEST_BYTES = 8 * 1024 * 1024;
const MAX_CLIENT_BYTES = 256 * 1024 * 1024;

export interface ClientJar {
  readonly version: string;
  readonly url: string;
  readonly sha1: string;
  readonly size: number;
}

export type Fetcher = (url: string, init: RequestInit) => Promise<Response>;

export function trustedObjectUrl(raw: string, allowedHosts: readonly string[]): string | null {
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return null;
  }
  if (url.protocol !== 'https:' || url.port !== '' || url.username !== '' || url.password !== '' || url.search !== '' || url.hash !== '') return null;
  if (!OBJECT_PATH.test(url.pathname)) return null;
  if (!allowedHosts.includes(url.hostname)) return null;
  const host = url.hostname === LEGACY_DATA_HOST ? DATA_HOST : url.hostname;
  return `https://${host}${url.pathname}`;
}

async function readLimited(response: Response, limit: number): Promise<Uint8Array<ArrayBuffer>> {
  if (response.body === null) return new Uint8Array();
  return readStream(response.body, limit, () => new AssetLoadError('corrupt', 'response too large'));
}

async function fetchChecked(fetcher: Fetcher, url: string, limit: number): Promise<Uint8Array<ArrayBuffer>> {
  let response: Response;
  try {
    response = await fetcher(url, { redirect: 'error', credentials: 'omit', referrerPolicy: 'no-referrer' });
  } catch (error) {
    throw new AssetLoadError('network', error instanceof Error ? error.message : 'network error');
  }
  if (!response.ok) throw new AssetLoadError('network', `HTTP ${String(response.status)} for ${url}`);
  return readLimited(response, limit);
}

function jsonRecord(bytes: Uint8Array): JsonRecord {
  const parsed = parseJsonBytes(bytes);
  if (!isRecord(parsed)) throw new AssetLoadError('corrupt', 'invalid JSON from Mojang');
  return parsed;
}

export async function resolveClientJar(version: string, fetcher: Fetcher): Promise<ClientJar> {
  const manifest = jsonRecord(await fetchChecked(fetcher, MANIFEST_URL, MAX_MANIFEST_BYTES));
  const versions = Array.isArray(manifest['versions']) ? manifest['versions'] : [];
  const listed = versions.find((entry): entry is Record<string, unknown> => isRecord(entry) && entry['id'] === version);
  if (listed === undefined) throw new AssetLoadError('unsupported_version', `Minecraft ${version} is not in the version manifest`);
  const listedUrl = typeof listed['url'] === 'string' ? trustedObjectUrl(listed['url'], [META_HOST]) : null;
  const listedSha1 = typeof listed['sha1'] === 'string' ? listed['sha1'] : '';
  if (listedUrl === null || !SHA1.test(listedSha1)) throw new AssetLoadError('corrupt', 'unexpected version manifest entry');
  const versionBytes = await fetchChecked(fetcher, listedUrl, MAX_VERSION_JSON_BYTES);
  if (sha1Hex(versionBytes) !== listedSha1) throw new AssetLoadError('corrupt', 'version JSON does not match its manifest SHA-1');
  const versionJson = jsonRecord(versionBytes);
  const downloads = isRecord(versionJson['downloads']) ? versionJson['downloads'] : {};
  const client = isRecord(downloads['client']) ? downloads['client'] : {};
  const url = typeof client['url'] === 'string' ? trustedObjectUrl(client['url'], [DATA_HOST, LEGACY_DATA_HOST]) : null;
  const sha1 = typeof client['sha1'] === 'string' ? client['sha1'] : '';
  const size = typeof client['size'] === 'number' ? client['size'] : 0;
  if (url === null || !SHA1.test(sha1) || !Number.isInteger(size) || size <= 0 || size > MAX_CLIENT_BYTES) {
    throw new AssetLoadError('corrupt', 'unexpected client download entry');
  }
  if (!url.includes(`/${sha1}/`)) throw new AssetLoadError('corrupt', 'client URL does not name its SHA-1');
  return { version, url, sha1, size };
}
