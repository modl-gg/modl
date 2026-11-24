import test from 'node:test';
import assert from 'node:assert/strict';

import { parseReplayAsync } from './ReplayParser.ts';

async function gzipReplayBytes(bytes: ArrayBuffer): Promise<ArrayBuffer> {
  return new Response(new Blob([bytes]).stream().pipeThrough(new CompressionStream('gzip'))).arrayBuffer();
}

function buildMinimalV4Replay(): ArrayBuffer {
  const bytes = new Uint8Array(4 + 2 + 8 + 2 + 7 + 4 + 4 + 4 + 4 + 4);
  const view = new DataView(bytes.buffer);
  let offset = 0;

  bytes[offset++] = 0x4d;
  bytes[offset++] = 0x4f;
  bytes[offset++] = 0x44;
  bytes[offset++] = 0x4c;
  view.setUint16(offset, 4);
  offset += 2;
  view.setBigInt64(offset, 1713379200000n);
  offset += 8;
  view.setUint16(offset, 7);
  offset += 2;
  bytes.set(new TextEncoder().encode('1.21.11'), offset);
  offset += 7;
  view.setInt32(offset, 0);
  offset += 4;
  view.setInt32(offset, 0);
  offset += 4;
  view.setInt32(offset, 0);
  offset += 4;
  view.setInt32(offset, 32);
  offset += 4;
  view.setInt32(offset, 0);

  return bytes.buffer;
}

void test('gzip-wrapped v4 replay payloads are parsed', async () => {
  const replayBytes = buildMinimalV4Replay();
  const gzippedArrayBuffer = await gzipReplayBytes(replayBytes);

  const parsed = await parseReplayAsync(gzippedArrayBuffer);

  assert.equal(parsed.header.version, 4);
  assert.equal(parsed.header.mcVersion, '1.21.11');
  assert.equal(parsed.snapshot.length, 0);
  assert.equal(parsed.events.length, 0);
});
