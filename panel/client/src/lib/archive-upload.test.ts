import test from 'node:test';
import assert from 'node:assert/strict';

import { MIN_CHUNK_BYTES, uploadArchive, type ArchiveUploadPlan, type ChunkResponse, type ChunkSender, type UploadProgress } from './archive-upload.ts';

const MEBIBYTE = 1024 * 1024;

type Reply = (end: number) => ChunkResponse<UploadProgress>;

interface SentChunk {
  offset: number;
  size: number;
}

const receivingState = (receivedBytes: number): UploadProgress => ({ receivedBytes: BigInt(receivedBytes) });

const accepted: Reply = (end) => ({ status: 200, retryAfterSeconds: null, state: receivingState(end) });
const tooLarge: Reply = () => ({ status: 413, retryAfterSeconds: null, state: null });
const throttled: Reply = () => ({ status: 429, retryAfterSeconds: 0, state: null });

const plan = (size: number, overrides: Partial<ArchiveUploadPlan> = {}): ArchiveUploadPlan => ({
  path: '/v1/panel/setup/import/upload',
  importId: 'import-1',
  size,
  receivedBytes: 0,
  maxChunkBytes: 2 * MEBIBYTE,
  ...overrides,
});

function scripted(...replies: Reply[]): { sent: SentChunk[]; send: ChunkSender<UploadProgress> } {
  const sent: SentChunk[] = [];
  const send: ChunkSender<UploadProgress> = async (offset, chunk) => {
    const reply = replies[sent.length] ?? accepted;
    sent.push({ offset, size: chunk.size });
    return Promise.resolve(reply(offset + chunk.size));
  };
  return { sent, send };
}

async function upload(planned: ArchiveUploadPlan, send: ChunkSender<UploadProgress>, signal = new AbortController().signal): Promise<number[]> {
  const progress: number[] = [];
  await uploadArchive(new Blob([new Uint8Array(planned.size)]), planned, send, (state) => progress.push(Number(state.receivedBytes)), signal);
  return progress;
}

await test('an archive is sent in order in chunks of the advertised size', async () => {
  const { sent, send } = scripted();
  const progress = await upload(plan(5 * MEBIBYTE), send);
  assert.deepEqual(sent, [
    { offset: 0, size: 2 * MEBIBYTE },
    { offset: 2 * MEBIBYTE, size: 2 * MEBIBYTE },
    { offset: 4 * MEBIBYTE, size: MEBIBYTE },
  ]);
  assert.deepEqual(progress, [2 * MEBIBYTE, 4 * MEBIBYTE, 5 * MEBIBYTE]);
});

await test('an interrupted upload continues at the received byte', async () => {
  const { sent, send } = scripted();
  await upload(plan(3 * MEBIBYTE, { receivedBytes: 2 * MEBIBYTE }), send);
  assert.deepEqual(sent, [{ offset: 2 * MEBIBYTE, size: MEBIBYTE }]);
});

await test('a chunk refused as too large is sent again in half the size', async () => {
  const { sent, send } = scripted(tooLarge, tooLarge);
  await upload(plan(MEBIBYTE, { maxChunkBytes: 2 * MEBIBYTE }), send);
  assert.deepEqual(sent.map((chunk) => chunk.offset), [0, 0, 0, MIN_CHUNK_BYTES]);
  assert.deepEqual(sent.slice(2).map((chunk) => chunk.size), [MIN_CHUNK_BYTES, MIN_CHUNK_BYTES]);
});

await test('halving stops at the smallest chunk', async () => {
  const { sent, send } = scripted(tooLarge);
  await assert.rejects(upload(plan(MIN_CHUNK_BYTES, { maxChunkBytes: MIN_CHUNK_BYTES }), send), /chunk-too-large/);
  assert.equal(sent.length, 1);
});

await test('a rate limited chunk is sent again unchanged', async () => {
  const { sent, send } = scripted(throttled);
  const progress = await upload(plan(MEBIBYTE), send);
  assert.deepEqual(sent, [{ offset: 0, size: MEBIBYTE }, { offset: 0, size: MEBIBYTE }]);
  assert.deepEqual(progress, [MEBIBYTE]);
});

await test('the offset the server reports wins over the bytes that were sent', async () => {
  const { sent, send } = scripted(() => accepted(MIN_CHUNK_BYTES));
  await upload(plan(MEBIBYTE), send);
  assert.deepEqual(sent.map((chunk) => chunk.offset), [0, MIN_CHUNK_BYTES]);
});

await test('a failed chunk ends the upload', async () => {
  const send: ChunkSender<UploadProgress> = async () => Promise.reject(new Error('offline'));
  await assert.rejects(upload(plan(MEBIBYTE), send), /offline/);
});

await test('an aborted upload sends nothing more', async () => {
  const controller = new AbortController();
  const { sent, send } = scripted();
  const abortingSend: ChunkSender<UploadProgress> = async (offset, chunk, signal) => {
    const response = await send(offset, chunk, signal);
    controller.abort();
    return response;
  };
  await assert.rejects(upload(plan(5 * MEBIBYTE), abortingSend, controller.signal), { name: 'AbortError' });
  assert.equal(sent.length, 1);
});
