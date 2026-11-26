export interface ZipEntry {
  readonly name: string;
  readonly method: number;
  readonly crc32: number;
  readonly compressedSize: number;
  readonly uncompressedSize: number;
  readonly localHeaderOffset: number;
}

export interface ZipEndRecord {
  readonly entryCount: number;
  readonly directoryOffset: number;
  readonly directorySize: number;
}

export const STORED = 0;
export const DEFLATED = 8;

const END_SIGNATURE = 0x06054b50;
const ZIP64_LOCATOR_SIGNATURE = 0x07064b50;
const ZIP64_LOCATOR_SIZE = 20;
const CENTRAL_SIGNATURE = 0x02014b50;
const LOCAL_SIGNATURE = 0x04034b50;
const END_RECORD_SIZE = 22;
const MAX_COMMENT = 0xffff;
const CENTRAL_HEADER_SIZE = 46;
export const LOCAL_HEADER_SIZE = 30;
const UTF8 = new TextDecoder('utf-8');

export class ZipFormatError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ZipFormatError';
  }
}

export class Zip64ArchiveError extends ZipFormatError {
  constructor() {
    super('ZIP64 archives are not supported');
    this.name = 'Zip64ArchiveError';
  }
}

export function endRecordSearchLength(archiveSize: number): number {
  return Math.min(archiveSize, END_RECORD_SIZE + MAX_COMMENT);
}

export function readEndRecord(tail: Uint8Array, archiveSize: number): ZipEndRecord {
  const view = new DataView(tail.buffer, tail.byteOffset, tail.byteLength);
  for (let offset = tail.byteLength - END_RECORD_SIZE; offset >= 0; offset--) {
    if (view.getUint32(offset, true) !== END_SIGNATURE) continue;
    const locator = offset - ZIP64_LOCATOR_SIZE;
    if (locator >= 0 && view.getUint32(locator, true) === ZIP64_LOCATOR_SIGNATURE) throw new Zip64ArchiveError();
    const entryCount = view.getUint16(offset + 10, true);
    const directorySize = view.getUint32(offset + 12, true);
    const directoryOffset = view.getUint32(offset + 16, true);
    if (directoryOffset + directorySize > archiveSize) throw new ZipFormatError('central directory outside the archive');
    return { entryCount, directoryOffset, directorySize };
  }
  throw new ZipFormatError('no end of central directory record');
}

export function readCentralDirectory(directory: Uint8Array, entryCount: number): ZipEntry[] {
  const view = new DataView(directory.buffer, directory.byteOffset, directory.byteLength);
  const entries: ZipEntry[] = [];
  let offset = 0;
  for (let index = 0; index < entryCount; index++) {
    if (offset + CENTRAL_HEADER_SIZE > directory.byteLength) throw new ZipFormatError('truncated central directory');
    if (view.getUint32(offset, true) !== CENTRAL_SIGNATURE) throw new ZipFormatError('bad central directory signature');
    const nameLength = view.getUint16(offset + 28, true);
    const extraLength = view.getUint16(offset + 30, true);
    const commentLength = view.getUint16(offset + 32, true);
    const nameStart = offset + CENTRAL_HEADER_SIZE;
    if (nameStart + nameLength > directory.byteLength) throw new ZipFormatError('truncated entry name');
    entries.push({
      name: UTF8.decode(directory.subarray(nameStart, nameStart + nameLength)),
      method: view.getUint16(offset + 10, true),
      crc32: view.getUint32(offset + 16, true),
      compressedSize: view.getUint32(offset + 20, true),
      uncompressedSize: view.getUint32(offset + 24, true),
      localHeaderOffset: view.getUint32(offset + 42, true),
    });
    offset = nameStart + nameLength + extraLength + commentLength;
  }
  return entries;
}

export function localDataOffset(chunk: Uint8Array, headerOffsetInChunk: number): number {
  if (headerOffsetInChunk + LOCAL_HEADER_SIZE > chunk.byteLength) throw new ZipFormatError('truncated local header');
  const view = new DataView(chunk.buffer, chunk.byteOffset, chunk.byteLength);
  if (view.getUint32(headerOffsetInChunk, true) !== LOCAL_SIGNATURE) throw new ZipFormatError('bad local header signature');
  const nameLength = view.getUint16(headerOffsetInChunk + 26, true);
  const extraLength = view.getUint16(headerOffsetInChunk + 28, true);
  return headerOffsetInChunk + LOCAL_HEADER_SIZE + nameLength + extraLength;
}

export interface ByteRange {
  readonly start: number;
  readonly end: number;
  readonly entries: readonly ZipEntry[];
}

const LOCAL_SLACK = 1024;

export function planRanges(entries: readonly ZipEntry[], mergeGap: number, archiveSize: number): ByteRange[] {
  const sorted = [...entries].sort((left, right) => left.localHeaderOffset - right.localHeaderOffset);
  const ranges: { start: number; end: number; entries: ZipEntry[] }[] = [];
  for (const entry of sorted) {
    const start = entry.localHeaderOffset;
    const end = Math.min(archiveSize, start + LOCAL_HEADER_SIZE + entry.name.length + entry.compressedSize + LOCAL_SLACK);
    const last = ranges.at(-1);
    if (last !== undefined && start - last.end <= mergeGap) {
      last.end = Math.max(last.end, end);
      last.entries.push(entry);
    } else {
      ranges.push({ start, end, entries: [entry] });
    }
  }
  return ranges;
}
