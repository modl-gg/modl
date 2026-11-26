import test from 'node:test';
import assert from 'node:assert/strict';
import type { EntitySnapshotData, ParsedReplay, ReplayEventData } from '../format/ReplayModel.ts';
import { BlockCursor } from './BlockCursor.ts';
import { buildTimelineData } from './buildTimeline.ts';
import { formatChat } from './ChatFormat.ts';
import { ReplayTimeline } from './ReplayTimeline.ts';
import { parseTimelineData } from './TimelineData.ts';

function timelineOf(events: ReplayEventData[], initialState: (world: number, x: number, y: number, z: number) => number = () => 0): ReplayTimeline {
  return new ReplayTimeline(buildTimelineData(replay(events), initialState));
}

function entity(entityId: number, x: number): EntitySnapshotData {
  return { entityId, type: 'minecraft:zombie', uuid: null, x, y: 64, z: 0, yaw: 0, pitch: 0, headYaw: 0, onGround: true, metadata: {}, equipment: [], effects: [], vehicle: null };
}

function replay(events: ReplayEventData[]): ParsedReplay {
  return {
    header: {
      formatVersion: 5, minecraftVersion: '26.1.2', dimension: 'minecraft:overworld', minY: -64, height: 384, startEpochMs: 0, durationMs: 10000,
      targetUuid: null, targetName: null, markerMs: null, targetEntityId: null, hardcore: false, lightRecorded: false, radiusBlocks: 64, truncated: false,
    },
    palette: [{ name: 'air', properties: {} }, { name: 'stone', properties: {} }, { name: 'dirt', properties: {} }],
    biomeKeys: [],
    worlds: new Map([[0, { dimension: { world: 0, key: 'minecraft:overworld', minY: -64, height: 384, hasSkyLight: true }, sections: [], blockEntities: [] }]]),
    keyframes: [],
    events,
  };
}

void test('block seeks restore the exact state backwards and forwards', () => {
  const timeline = timelineOf([
    { kind: 'block', timeMs: 1000, x: 0, y: 0, z: 0, state: 1 },
    { kind: 'block', timeMs: 2000, x: 0, y: 0, z: 0, state: 2 },
    { kind: 'block', timeMs: 3000, x: 0, y: 0, z: 0, state: 0 },
  ], () => 1);
  const cursor = new BlockCursor(timeline.blocks, 0);
  const at = new Map<string, number>([['0,0,0', 1]]);
  const apply = (time: number): number => {
    for (const change of cursor.moveTo(time)) at.set(`${String(change.x)},${String(change.y)},${String(change.z)}`, change.state);
    return at.get('0,0,0') ?? -1;
  };
  assert.equal(apply(3500), 0);
  assert.equal(apply(2500), 2);
  assert.equal(apply(500), 1);
  assert.equal(apply(2000), 2);
  assert.equal(apply(10000), 0);
  assert.deepEqual(timeline.markers.map((marker) => marker.kind), ['block_place', 'block_place', 'block_break', 'capture']);
});

void test('entity positions interpolate between samples and respect presence', () => {
  const timeline = timelineOf([
    { kind: 'spawn', timeMs: 0, entity: entity(7, 0) },
    { kind: 'move', timeMs: 1000, entityId: 7, dx: 2, dy: 0, dz: 0, yaw: 90, pitch: null, headYaw: null, onGround: true },
    { kind: 'remove', timeMs: 2000, entityIds: [7] },
  ]);
  const track = timeline.entities.get(7);
  assert.ok(track !== undefined);
  assert.equal(track.pose(500)?.x, 1);
  assert.equal(track.pose(500)?.yaw, 45);
  assert.ok(track.present(1999));
  assert.ok(!track.present(2000));
});

void test('metadata and equipment merge over time', () => {
  const timeline = timelineOf([
    { kind: 'spawn', timeMs: 0, entity: entity(3, 0) },
    { kind: 'metadata', timeMs: 100, entityId: 3, metadata: { baby: true } },
    { kind: 'metadata', timeMs: 200, entityId: 3, metadata: { customName: 'Bob' } },
    { kind: 'equipment', timeMs: 300, entityId: 3, equipment: [{ slot: 'head', item: { item: 'minecraft:diamond_helmet', count: 1, enchanted: false, dyeColor: null, customName: null, damage: 0, maxDamage: 0 } }] },
  ]);
  const track = timeline.entities.get(3);
  assert.ok(track !== undefined);
  assert.deepEqual(track.metadata.at(250), { baby: true, customName: 'Bob' });
  assert.equal(track.metadata.at(150)?.customName, undefined);
  assert.equal(track.equipment.at(400)?.head?.item, 'minecraft:diamond_helmet');
  assert.equal(track.equipment.at(250)?.head, undefined);
});

void test('chat components resolve colours, styles, translations and legacy codes', () => {
  const segments = formatChat(JSON.stringify({ translate: 'chat.type.text', with: [{ text: 'Steve', color: 'gold' }, 'hi §lthere'] }), (key) => (key === 'chat.type.text' ? '<%s> %s' : undefined));
  assert.deepEqual(segments.map((segment) => segment.text), ['<', 'Steve', '> ', 'hi ', 'there']);
  assert.equal(segments[1]?.color, '#ffaa00');
  assert.equal(segments[4]?.bold, true);
  assert.deepEqual(formatChat('plain', () => undefined).map((segment) => segment.text), ['plain']);
});

void test('hostile nested translations stop at the work budget', () => {
  let node: unknown = '';
  for (let depth = 0; depth < 15; depth++) node = { translate: 'x', fallback: '%1$s'.repeat(8), with: [node] };
  const started = performance.now();
  formatChat(JSON.stringify(node), () => undefined);
  assert.ok(performance.now() - started < 1000);
});

void test('block changes in another world stay with that world cursor', () => {
  const timeline = timelineOf([
    { kind: 'block', timeMs: 1000, x: 1, y: 2, z: 3, state: 1 },
    { kind: 'dimension', timeMs: 1500, dimension: { world: 1, key: 'minecraft:the_nether', minY: 0, height: 256, hasSkyLight: false } },
    { kind: 'block', timeMs: 2000, x: 1, y: 2, z: 3, state: 2 },
  ], (world) => (world === 1 ? 2 : 0));
  const overworld = new BlockCursor(timeline.blocks, 0);
  const nether = new BlockCursor(timeline.blocks, 1);
  assert.deepEqual(overworld.moveTo(3000), [{ x: 1, y: 2, z: 3, state: 1 }]);
  assert.deepEqual(nether.moveTo(3000), [{ x: 1, y: 2, z: 3, state: 2 }]);
  assert.deepEqual(nether.moveTo(0), [{ x: 1, y: 2, z: 3, state: 2 }]);
  assert.deepEqual(overworld.moveTo(0), [{ x: 1, y: 2, z: 3, state: 0 }]);
  assert.equal(timeline.worldAt(1700), 1);
});

void test('dense markers collapse to a bounded number per kind', () => {
  const events: ReplayEventData[] = [];
  for (let index = 0; index < 50_000; index++) {
    events.push({ kind: 'block', timeMs: index / 5, x: index % 16, y: 0, z: 0, state: index % 2 });
    events.push({ kind: 'chat', timeMs: index / 5, chatKind: 'system', json: 'hi', senderUuid: null });
  }
  const timeline = timelineOf(events);
  const kinds = new Map<string, number>();
  for (const marker of timeline.markers) kinds.set(marker.kind, (kinds.get(marker.kind) ?? 0) + 1);
  for (const kind of ['block_place', 'block_break', 'chat']) assert.ok((kinds.get(kind) ?? 0) <= 600, kind);
  assert.equal(timeline.markers.at(-1)?.kind, 'capture');
  assert.equal(timeline.blocks.size, 50_000);
});

void test('timeline data survives a structured clone and validates', () => {
  const data = buildTimelineData(replay([
    { kind: 'spawn', timeMs: 0, entity: entity(9, 4) },
    { kind: 'player_info', timeMs: 0, player: { uuid: 'u', name: 'Steve', skinHash: null, slim: false, gameMode: 'creative', skinPng: null } },
    { kind: 'target_inventory', timeMs: 10, replaceAll: true, slots: [{ slot: 36, item: { item: 'minecraft:diamond', count: 3, enchanted: false, dyeColor: null, customName: null, damage: 0, maxDamage: 0 } }] },
    { kind: 'block', timeMs: 20, x: 0, y: 0, z: 0, state: 1 },
  ]), () => 0);
  const parsed = parseTimelineData(structuredClone(data));
  assert.ok(parsed !== null);
  assert.equal(parsed.tracks.length, 1);
  assert.equal(parseTimelineData({ ...data, tracks: [{ id: 'x' }] }), null);
});
