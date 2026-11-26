import { readReplayV4 } from './readReplayV4.ts';
import { decodeFrames } from './readReplayV5.ts';
import { readStream } from './readStream.ts';
import { MAX_COMPRESSED_BYTES, MAX_UNCOMPRESSED_BYTES } from './ReplayLimits.ts';
import { type ParsedReplay, REPLAY_MAGIC, ReplayFormatError } from './ReplayModel.ts';

const CURRENT_VERSION = 5;

export async function gunzip(bytes: Uint8Array<ArrayBuffer>): Promise<Uint8Array<ArrayBuffer>> {
  const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip'));
  try {
    return await readStream(stream, MAX_UNCOMPRESSED_BYTES, () => new ReplayFormatError('replay is too large'));
  } catch (error) {
    if (error instanceof ReplayFormatError) throw error;
    throw new ReplayFormatError('replay data is not valid gzip');
  }
}

function startsWith(bytes: Uint8Array, prefix: readonly number[]): boolean {
  return prefix.every((byte, index) => bytes[index] === byte);
}

export async function readReplay(buffer: ArrayBuffer): Promise<ParsedReplay> {
  const bytes = new Uint8Array(buffer);
  if (bytes.length > MAX_COMPRESSED_BYTES) throw new ReplayFormatError('replay file is too large');
  if (bytes.length < 6) throw new ReplayFormatError('replay file is too short');
  if (bytes[0] === 0x1f && bytes[1] === 0x8b) return readReplayV4(await gunzip(bytes));
  if (!startsWith(bytes, REPLAY_MAGIC)) throw new ReplayFormatError('not a modl replay');
  const version = ((bytes[4] ?? 0) << 8) | (bytes[5] ?? 0);
  if (version < CURRENT_VERSION) return readReplayV4(bytes);
  if (version !== CURRENT_VERSION) throw new ReplayFormatError(`unsupported replay format ${String(version)}`);
  return decodeFrames(await gunzip(bytes.subarray(6)));
}
