import { isRecord } from '../json.ts';

const DATABASE = 'modl-replay-assets';
const BUNDLES = 'bundles';
const META = 'meta';
const SCHEMA = 2;
const MAX_BUNDLES = 4;

interface BundleUse {
  readonly key: string;
  readonly usedAt: number;
}

function isUse(value: unknown): value is BundleUse {
  return isRecord(value) && typeof value['key'] === 'string' && typeof value['usedAt'] === 'number';
}

function bytesOf(value: unknown): ArrayBuffer | null {
  return isRecord(value) && value['bytes'] instanceof ArrayBuffer ? value['bytes'] : null;
}

async function requestResult<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => { resolve(request.result); };
    request.onerror = () => { reject(request.error ?? new Error('IndexedDB request failed')); };
  });
}

async function transactionDone(transaction: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    transaction.oncomplete = () => { resolve(); };
    transaction.onerror = () => { reject(transaction.error ?? new Error('IndexedDB transaction failed')); };
    transaction.onabort = () => { reject(transaction.error ?? new Error('IndexedDB transaction aborted')); };
  });
}

async function open(): Promise<IDBDatabase | null> {
  if (typeof indexedDB === 'undefined') return null;
  try {
    const request = indexedDB.open(DATABASE, SCHEMA);
    const blocked = new Promise<null>((resolve) => {
      request.onblocked = () => { resolve(null); };
    });
    request.onupgradeneeded = () => {
      const database = request.result;
      for (const name of [...database.objectStoreNames]) database.deleteObjectStore(name);
      database.createObjectStore(BUNDLES, { keyPath: 'key' });
      database.createObjectStore(META, { keyPath: 'key' });
    };
    const database = await Promise.race([requestResult(request), blocked]);
    if (database === null) {
      request.onsuccess = () => { request.result.close(); };
      return null;
    }
    database.onversionchange = () => { database.close(); };
    return database;
  } catch {
    return null;
  }
}

async function touch(database: IDBDatabase, key: string): Promise<void> {
  const transaction = database.transaction(META, 'readwrite');
  transaction.objectStore(META).put({ key, usedAt: Date.now() } satisfies BundleUse);
  await transactionDone(transaction);
}

export async function readBundle(key: string): Promise<ArrayBuffer | null> {
  const database = await open();
  if (database === null) return null;
  try {
    const transaction = database.transaction(BUNDLES, 'readonly');
    const bytes = bytesOf(await requestResult(transaction.objectStore(BUNDLES).get(key)));
    if (bytes !== null) await touch(database, key).catch(() => undefined);
    return bytes;
  } catch {
    return null;
  } finally {
    database.close();
  }
}

export async function deleteBundle(key: string): Promise<void> {
  const database = await open();
  if (database === null) return;
  try {
    const transaction = database.transaction([BUNDLES, META], 'readwrite');
    transaction.objectStore(BUNDLES).delete(key);
    transaction.objectStore(META).delete(key);
    await transactionDone(transaction);
  } catch {
    return;
  } finally {
    database.close();
  }
}

async function evictFor(database: IDBDatabase, key: string): Promise<void> {
  const transaction = database.transaction([BUNDLES, META], 'readwrite');
  const meta = transaction.objectStore(META);
  const uses: unknown[] = await requestResult(meta.getAll());
  const others = uses.filter(isUse).filter((use) => use.key !== key).sort((left, right) => right.usedAt - left.usedAt);
  for (const stale of others.slice(MAX_BUNDLES - 1)) {
    transaction.objectStore(BUNDLES).delete(stale.key);
    meta.delete(stale.key);
  }
  await transactionDone(transaction);
}

export async function writeBundle(key: string, bytes: ArrayBuffer): Promise<void> {
  const database = await open();
  if (database === null) return;
  try {
    await evictFor(database, key);
    const transaction = database.transaction([BUNDLES, META], 'readwrite');
    transaction.objectStore(BUNDLES).put({ key, bytes });
    transaction.objectStore(META).put({ key, usedAt: Date.now() } satisfies BundleUse);
    await transactionDone(transaction);
  } catch {
    return;
  } finally {
    database.close();
  }
}
