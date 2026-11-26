import { AssetLoadError } from '../AssetLibrary.ts';
import { parseVersion } from '../../format/MinecraftVersion.ts';
import { SELECTION_REVISION, type SourceRole, assetPath, sourceJars } from './AssetSelection.ts';
import { deleteBundle, readBundle, writeBundle } from './BundleCache.ts';
import { packFiles, unpackFiles } from './FileBundle.ts';
import { JarReader, type JarPlan } from './JarReader.ts';
import { type Fetcher, resolveClientJar } from './mojangVersions.ts';

export interface LoadedBundle {
  readonly role: SourceRole;
  readonly version: string;
  readonly bytes: ArrayBuffer;
}

export type BundleProgress = (loadedBytes: number, totalBytes: number) => void;

interface PendingSource {
  readonly role: SourceRole;
  readonly version: string;
  readonly key: string;
  readonly reader: JarReader;
  readonly plan: JarPlan;
}

function cacheKey(version: string, role: SourceRole): string {
  return `${version}:${role}:${SELECTION_REVISION}`;
}

export async function loadBundles(versionId: string, fetcher: Fetcher, progress: BundleProgress): Promise<LoadedBundle[]> {
  const sources = sourceJars(parseVersion(versionId));
  const loaded: LoadedBundle[] = [];
  const pending: PendingSource[] = [];
  let received = 0;
  let total = 0;
  const count = (bytes: number): void => {
    received += bytes;
    progress(received, Math.max(total, received));
  };
  for (const source of sources) {
    const key = cacheKey(source.version, source.role);
    const cached = await readBundle(key);
    if (cached !== null && unpackFiles(cached) === null) await deleteBundle(key);
    else if (cached !== null) {
      loaded.push({ role: source.role, version: source.version, bytes: cached });
      continue;
    }
    const jar = await resolveClientJar(source.version, fetcher);
    const reader = new JarReader(jar, fetcher);
    const directory = await reader.directory(count);
    const plan = reader.plan(directory, source.accepts);
    if (plan.entries.length === 0) throw new AssetLoadError('unsupported_version', `no assets found in the ${source.version} client`);
    total += plan.downloadBytes;
    pending.push({ role: source.role, version: source.version, key, reader, plan });
  }
  total += received;
  progress(received, total);
  for (const source of pending) {
    const files = await source.reader.extract(source.plan, count);
    const renamed = new Map<string, Uint8Array<ArrayBuffer>>();
    for (const [name, data] of files) {
      const path = assetPath(name);
      if (path !== null) renamed.set(path, data);
    }
    const bytes = packFiles(renamed);
    await writeBundle(source.key, bytes);
    loaded.push({ role: source.role, version: source.version, bytes });
  }
  progress(total, total);
  return loaded;
}
