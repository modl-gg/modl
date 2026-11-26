import { AssetLoadError } from '../AssetLibrary.ts';
import { readStream } from '../../format/readStream.ts';
import { crc32 } from './crc32.ts';
import type { ClientJar, Fetcher } from './mojangVersions.ts';
import {
  DEFLATED,
  STORED,
  type ZipEndRecord,
  type ZipEntry,
  Zip64ArchiveError,
  endRecordSearchLength,
  localDataOffset,
  planRanges,
  readCentralDirectory,
  readEndRecord,
} from './ZipDirectory.ts';

const MERGE_GAP = 64 * 1024;
const MAX_ENTRY_BYTES = 16 * 1024 * 1024;
const MAX_TOTAL_BYTES = 96 * 1024 * 1024;
const PARALLEL_RANGES = 4;

export type EntryFilter = (name: string) => boolean;

export type ByteProgress = (bytes: number) => void;

export interface JarPlan {
  readonly entries: readonly ZipEntry[];
  readonly downloadBytes: number;
}

const RANGE_ATTEMPTS = 3;
const RETRY_DELAY_MS = 400;

async function delay(milliseconds: number, signal: AbortSignal | null): Promise<void> {
  if (signal?.aborted === true) throw new AssetLoadError('aborted', 'aborted');
  return new Promise((resolve, reject) => {
    const abort = (): void => {
      clearTimeout(timer);
      reject(new AssetLoadError('aborted', 'aborted'));
    };
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', abort);
      resolve();
    }, milliseconds);
    signal?.addEventListener('abort', abort, { once: true });
  });
}

interface RangeFailure {
  readonly message: string;
  readonly retry: boolean;
}

async function fetchRangeOnce(fetcher: Fetcher, jar: ClientJar, start: number, endExclusive: number, signal: AbortSignal | null): Promise<Uint8Array | RangeFailure> {
  let response: Response;
  try {
    response = await fetcher(jar.url, {
      signal,
      redirect: 'error',
      credentials: 'omit',
      cache: 'no-store',
      referrerPolicy: 'no-referrer',
      headers: { Range: `bytes=${String(start)}-${String(endExclusive - 1)}` },
    });
  } catch (error) {
    if (signal?.aborted === true) throw new AssetLoadError('aborted', 'aborted');
    return { message: error instanceof Error ? error.message : 'network error', retry: true };
  }
  if (response.status !== 206 || response.body === null) {
    await response.body?.cancel();
    return { message: `range request answered HTTP ${String(response.status)}`, retry: response.status >= 500 || response.status === 429 };
  }
  const expected = endExclusive - start;
  let bytes: Uint8Array;
  try {
    bytes = await readStream(response.body, expected, () => new AssetLoadError('corrupt', 'range response too long'));
  } catch (error) {
    if (signal?.aborted === true) throw new AssetLoadError('aborted', 'aborted');
    if (error instanceof AssetLoadError) throw error;
    return { message: error instanceof Error ? error.message : 'range download interrupted', retry: true };
  }
  return bytes.byteLength === expected ? bytes : { message: 'short range response', retry: true };
}

async function fetchRange(fetcher: Fetcher, jar: ClientJar, start: number, endExclusive: number, signal: AbortSignal | null): Promise<Uint8Array> {
  let failure = 'network error';
  for (let attempt = 0; attempt < RANGE_ATTEMPTS; attempt++) {
    if (attempt > 0) await delay(RETRY_DELAY_MS * attempt, signal);
    const result = await fetchRangeOnce(fetcher, jar, start, endExclusive, signal);
    if (result instanceof Uint8Array) return result;
    failure = result.message;
    if (!result.retry) break;
  }
  throw new AssetLoadError('network', failure);
}

export async function inflateRaw(compressed: Uint8Array<ArrayBuffer>, expectedSize: number): Promise<Uint8Array<ArrayBuffer>> {
  const stream = new Blob([compressed]).stream().pipeThrough(new DecompressionStream('deflate-raw'));
  let output: Uint8Array<ArrayBuffer>;
  try {
    output = await readStream(stream, expectedSize, () => new AssetLoadError('corrupt', 'inflated size mismatch'));
  } catch (error) {
    if (error instanceof AssetLoadError) throw error;
    throw new AssetLoadError('corrupt', 'entry is not valid deflate data');
  }
  if (output.byteLength !== expectedSize) throw new AssetLoadError('corrupt', 'inflated size mismatch');
  return output;
}

function endRecordOf(tail: Uint8Array, archiveSize: number): ZipEndRecord {
  try {
    return readEndRecord(tail, archiveSize);
  } catch (error) {
    if (error instanceof Zip64ArchiveError) throw new AssetLoadError('unsupported_version', 'the client jar is a ZIP64 archive');
    throw error;
  }
}

export class JarReader {
  private readonly jar: ClientJar;
  private readonly fetcher: Fetcher;

  constructor(jar: ClientJar, fetcher: Fetcher) {
    this.jar = jar;
    this.fetcher = fetcher;
  }

  async directory(progress: ByteProgress): Promise<ZipEntry[]> {
    const tailLength = endRecordSearchLength(this.jar.size);
    const tail = await fetchRange(this.fetcher, this.jar, this.jar.size - tailLength, this.jar.size, null);
    progress(tail.byteLength);
    const end = endRecordOf(tail, this.jar.size);
    const tailStart = this.jar.size - tailLength;
    const directory = end.directoryOffset >= tailStart
      ? tail.subarray(end.directoryOffset - tailStart, end.directoryOffset - tailStart + end.directorySize)
      : await fetchRange(this.fetcher, this.jar, end.directoryOffset, end.directoryOffset + end.directorySize, null);
    if (end.directoryOffset < tailStart) progress(directory.byteLength);
    return readCentralDirectory(directory, end.entryCount);
  }

  plan(entries: readonly ZipEntry[], filter: EntryFilter): JarPlan {
    const selected = entries.filter((entry) => !entry.name.endsWith('/') && filter(entry.name));
    const downloadBytes = planRanges(selected, MERGE_GAP, this.jar.size).reduce((sum, range) => sum + (range.end - range.start), 0);
    return { entries: selected, downloadBytes };
  }

  async extract(plan: JarPlan, progress: ByteProgress): Promise<Map<string, Uint8Array<ArrayBuffer>>> {
    const total = plan.entries.reduce((sum, entry) => sum + entry.uncompressedSize, 0);
    if (total > MAX_TOTAL_BYTES) throw new AssetLoadError('corrupt', 'selected assets exceed the size limit');
    const files = new Map<string, Uint8Array<ArrayBuffer>>();
    const ranges = planRanges(plan.entries, MERGE_GAP, this.jar.size);
    const siblings = new AbortController();
    let next = 0;
    const worker = async (): Promise<void> => {
      while (next < ranges.length && !siblings.signal.aborted) {
        const range = ranges[next];
        next += 1;
        if (range === undefined) return;
        const chunk = await fetchRange(this.fetcher, this.jar, range.start, range.end, siblings.signal);
        progress(chunk.byteLength);
        for (const entry of range.entries) {
          files.set(entry.name, await this.entryData(entry, chunk, range.start, siblings.signal));
        }
      }
    };
    const guarded = async (): Promise<void> => {
      try {
        await worker();
      } catch (error) {
        siblings.abort();
        throw error;
      }
    };
    await Promise.all(Array.from({ length: Math.min(PARALLEL_RANGES, ranges.length) }, guarded));
    return files;
  }

  private async entryData(entry: ZipEntry, chunk: Uint8Array, chunkStart: number, signal: AbortSignal): Promise<Uint8Array<ArrayBuffer>> {
    if (entry.uncompressedSize > MAX_ENTRY_BYTES || entry.compressedSize > MAX_ENTRY_BYTES) {
      throw new AssetLoadError('corrupt', `${entry.name} is too large`);
    }
    const headerOffset = entry.localHeaderOffset - chunkStart;
    let source = chunk;
    let dataStart: number;
    try {
      dataStart = localDataOffset(chunk, headerOffset);
    } catch {
      dataStart = -1;
    }
    if (dataStart < 0 || dataStart + entry.compressedSize > chunk.byteLength) {
      const headerChunk = await fetchRange(this.fetcher, this.jar, entry.localHeaderOffset, Math.min(this.jar.size, entry.localHeaderOffset + 64 * 1024 + entry.compressedSize), signal);
      source = headerChunk;
      dataStart = localDataOffset(headerChunk, 0);
      if (dataStart + entry.compressedSize > headerChunk.byteLength) throw new AssetLoadError('corrupt', `${entry.name} is truncated`);
    }
    const compressed = source.slice(dataStart, dataStart + entry.compressedSize);
    let data: Uint8Array<ArrayBuffer>;
    if (entry.method === STORED) {
      data = compressed;
    } else if (entry.method === DEFLATED) {
      data = await inflateRaw(compressed, entry.uncompressedSize);
    } else {
      throw new AssetLoadError('corrupt', `${entry.name} uses an unsupported compression method`);
    }
    if (crc32(data) !== entry.crc32) throw new AssetLoadError('corrupt', `${entry.name} failed its CRC-32 check`);
    return data;
  }
}
