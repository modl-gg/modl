import test from 'node:test';
import assert from 'node:assert/strict';
import { gzipSync } from 'node:zlib';
import { type MessageInitShape, create } from '@bufbuild/protobuf';
import { sizeDelimitedEncode } from '@bufbuild/protobuf/wire';
import { ReplayFrameSchema } from '../../../../../proto/gen/ts/modl/v1/replay_format_pb.ts';
import { readReplay } from './readReplay.ts';
import { MAX_COMPRESSED_BYTES, MAX_KEYFRAMES } from './ReplayLimits.ts';
import { ReplayFormatError } from './ReplayModel.ts';

type FrameInit = MessageInitShape<typeof ReplayFrameSchema>;

function v5(frames: readonly FrameInit[]): ArrayBuffer {
  const body = frames.map((frame) => sizeDelimitedEncode(ReplayFrameSchema, create(ReplayFrameSchema, frame)));
  const gzipped = gzipSync(Buffer.concat(body));
  const bytes = new Uint8Array(6 + gzipped.length);
  bytes.set([0x4d, 0x4f, 0x44, 0x4c, 0, 5]);
  bytes.set(gzipped, 6);
  return bytes.buffer;
}

const HEADER: FrameInit = { frame: { case: 'header', value: { formatVersion: 5, minecraftVersion: '26.1.2', dimension: { key: 'minecraft:overworld', minY: -64, height: 384, hasSkyLight: true } } } };

function palette(...names: string[]): FrameInit {
  return { frame: { case: 'blockPalette', value: { states: names.map((name) => ({ name })) } } };
}

function section(y: number, states: number[], light: boolean): FrameInit {
  return { frame: { case: 'section', value: { x: 0, y, z: 0, states, skyLight: light ? new Uint8Array(2048).fill(0xff) : new Uint8Array(), blockLight: new Uint8Array(), biomes: [] } } };
}

void test('empty sections cost no state storage and blocks use compact storage', async () => {
  const stone = new Array<number>(4096).fill(0);
  stone[0] = 1;
  const replay = await readReplay(v5([HEADER, palette('minecraft:air', 'minecraft:stone'), section(0, [], true), section(1, new Array<number>(4096).fill(0), true), section(2, stone, false), section(3, [], false)]));
  const sections = replay.worlds.get(0)?.sections ?? [];
  assert.equal(sections.length, 3);
  assert.equal(sections[0]?.states, null);
  assert.equal(sections[1]?.states, null);
  assert.ok(sections[2]?.states instanceof Uint16Array);
  assert.equal(sections[2].states[0], 1);
});

void test('the block palette must start with air', async () => {
  await assert.rejects(readReplay(v5([HEADER, palette('minecraft:stone', 'minecraft:air')])), ReplayFormatError);
});

void test('the keyframe count is capped', async () => {
  const frames: FrameInit[] = [HEADER];
  for (let index = 0; index <= MAX_KEYFRAMES; index++) frames.push({ frame: { case: 'keyframe', value: { timeMs: index } } });
  await assert.rejects(readReplay(v5(frames)), /too many keyframes/);
});

void test('block entities outside the world are refused and sign text is bounded', async () => {
  const strings: FrameInit = { frame: { case: 'strings', value: { values: ['minecraft:oak_sign'] } } };
  const sign: FrameInit = { frame: { case: 'blockEntity', value: { x: 1, y: 2, z: 3, type: 0, frontText: ['a'.repeat(5000), 'b', 'c', 'd', 'e'], skinHash: 'f'.repeat(500) } } };
  const replay = await readReplay(v5([HEADER, strings, sign]));
  const entity = replay.worlds.get(0)?.blockEntities[0];
  assert.equal(entity?.frontText.length, 4);
  assert.equal(entity.frontText[0]?.length, 512);
  assert.equal(entity.skinHash?.length, 64);
  const outside: FrameInit = { frame: { case: 'blockEntity', value: { x: 0, y: 1_000_000, z: 0, type: 0 } } };
  await assert.rejects(readReplay(v5([HEADER, strings, outside])), ReplayFormatError);
});

void test('files above the shared compressed limit are refused before decoding', async () => {
  await assert.rejects(readReplay(new Uint8Array(MAX_COMPRESSED_BYTES + 1).buffer), /too large/);
});

class V4Writer {
  private readonly bytes: number[] = [];

  u8(value: number): this {
    this.bytes.push(value & 0xff);
    return this;
  }

  u16(value: number): this {
    return this.u8(value >> 8).u8(value);
  }

  i32(value: number): this {
    return this.u16((value >>> 16) & 0xffff).u16(value & 0xffff);
  }

  f32(value: number): this {
    const view = new DataView(new ArrayBuffer(4));
    view.setFloat32(0, value);
    for (let index = 0; index < 4; index++) this.u8(view.getUint8(index));
    return this;
  }

  string16(value: string): this {
    const encoded = new TextEncoder().encode(value);
    this.u16(encoded.length);
    for (const byte of encoded) this.u8(byte);
    return this;
  }

  record(type: number, timeMs: number, payload: V4Writer, declaredLength = payload.length): this {
    this.u8(type).i32(timeMs).u16(declaredLength);
    for (const byte of payload.bytes) this.u8(byte);
    return this;
  }

  get length(): number {
    return this.bytes.length;
  }

  buffer(): ArrayBuffer {
    return Uint8Array.from(this.bytes).buffer;
  }
}

function v4Header(blocks: readonly (readonly [number, number, number])[] = []): V4Writer {
  const writer = new V4Writer().u8(0x4d).u8(0x4f).u8(0x44).u8(0x4c).u16(4);
  writer.i32(0).i32(0).string16('1.20.4').i32(0).i32(0).i32(0).i32(64).i32(blocks.length);
  for (const [x, y, z] of blocks) writer.i32(x).u16(y).i32(z).i32(1);
  return writer;
}

void test('v4 snapshots share the decoded section budget', async () => {
  const blocks: (readonly [number, number, number])[] = [];
  for (let index = 0; index < 40_000; index++) blocks.push([(index % 200) * 16, 0, Math.floor(index / 200) * 16]);
  await assert.rejects(readReplay(v4Header(blocks).buffer()), /world sections too large/);
});

function uuid(writer: V4Writer, low: number): V4Writer {
  return writer.i32(0).i32(0).i32(0).i32(low);
}

void test('a v4 record that reads past its declared length is refused', async () => {
  const removal = new V4Writer().i32(7);
  await assert.rejects(readReplay(v4Header().record(0x04, 10, removal, 2).buffer()), ReplayFormatError);
  const short = v4Header().record(0x04, 10, removal, 400);
  await assert.rejects(readReplay(short.buffer()), ReplayFormatError);
  const valid = await readReplay(v4Header().record(0x04, 10, removal).record(0x7f, 20, new V4Writer().u8(1).u8(2)).buffer());
  assert.deepEqual(valid.events.map((event) => event.kind), ['remove']);
});

void test('v4 player ids never collide with recorded entity ids', async () => {
  const spawnEntity = new V4Writer().i32(2_000_000_000).u16(0).f32(0).f32(64).f32(0).string16('zombie');
  const spawnPlayer = uuid(new V4Writer(), 1).u8(5).u8(0x53).u8(0x74).u8(0x65).u8(0x76).u8(0x65).f32(1).f32(64).f32(1).f32(0).f32(0).u16(0);
  const replay = await readReplay(v4Header().record(0x02, 0, spawnEntity).record(0x05, 5, spawnPlayer).buffer());
  const ids = replay.events.flatMap((event) => (event.kind === 'spawn' ? [event.entity.entityId] : []));
  assert.equal(ids.length, 2);
  assert.equal(new Set(ids).size, 2);
});
