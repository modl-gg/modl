import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { readReplay } from './readReplay.ts';
import type { ParsedReplay } from './ReplayModel.ts';
import { ReplayFormatError } from './ReplayModel.ts';

const FIXTURES = path.resolve(import.meta.dirname, '../../../../../libs/replay-format/src/test/resources/fixtures');

function fixture(name: string): ArrayBuffer {
  const bytes = readFileSync(path.join(FIXTURES, name));
  return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
}

function blockAt(replay: ParsedReplay, x: number, y: number, z: number): string {
  const world = replay.worlds.get(0);
  const section = world?.sections.find((candidate) => candidate.x === x >> 4 && candidate.y === y >> 4 && candidate.z === z >> 4);
  const states = section?.states ?? null;
  if (states === null) return 'air';
  const state = replay.palette[states[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)] ?? 0];
  if (state === undefined) return 'missing';
  const properties = Object.entries(state.properties).sort(([left], [right]) => left.localeCompare(right)).map(([key, value]) => `${key}=${value}`).join(',');
  return properties === '' ? state.name : `${state.name}[${properties}]`;
}

for (const [file, baseY, version] of [['v5/latest.modlreplay', -60, '26.1.2'], ['v5/1.21.modlreplay', -60, '1.21.11'], ['v5/legacy.modlreplay', 4, '1.8.8']] as const) {
  void test(`v5 fixture ${file} carries the reference scene`, async () => {
    const replay = await readReplay(fixture(file));
    assert.equal(replay.header.formatVersion, 5);
    assert.equal(replay.header.minecraftVersion, version);
    assert.equal(replay.palette[0]?.name, 'air');
    assert.match(blockAt(replay, 0, baseY, 0), /^oak_stairs\[.*facing=north/);
    assert.equal(blockAt(replay, 0, baseY, 4), 'white_wool');
    assert.equal(blockAt(replay, 15, baseY, 4), 'black_wool');
    const types = new Set(replay.events.flatMap((event) => (event.kind === 'spawn' ? [event.entity.type] : [])));
    for (const keyframe of replay.keyframes) for (const entity of keyframe.entities) types.add(entity.type);
    for (const type of ['minecraft:zombie', 'minecraft:creeper', 'minecraft:pig', 'minecraft:sheep', 'minecraft:player']) assert.ok(types.has(type), type);
    assert.ok(replay.events.some((event) => event.kind === 'chat'));
    assert.ok(replay.events.some((event) => event.kind === 'target_inventory' && event.slots.some((slot) => slot.slot === 36 && slot.item?.item === 'minecraft:diamond')));
    for (let index = 1; index < replay.events.length; index++) assert.ok((replay.events[index]?.timeMs ?? 0) >= (replay.events[index - 1]?.timeMs ?? 0));
  });
}

void test('v4 golden files convert into the same model', async () => {
  for (const file of ['v4/golden.modlreplay', 'v4/minimal.modlreplay']) {
    const replay = await readReplay(fixture(file));
    assert.ok(replay.header.formatVersion <= 4);
    assert.ok(replay.worlds.has(0));
  }
});

void test('truncated and foreign files fail with a format error', async () => {
  const bytes = new Uint8Array(fixture('v5/latest.modlreplay'));
  await assert.rejects(readReplay(bytes.slice(0, bytes.length / 2).buffer), ReplayFormatError);
  await assert.rejects(readReplay(new TextEncoder().encode('not a replay file').buffer), ReplayFormatError);
});
