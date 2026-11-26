import test from 'node:test';
import assert from 'node:assert/strict';
import type { BlockState } from '../../format/BlockState.ts';
import { deriveLegacyState } from './LegacyConnections.ts';

function lookup(neighbors: Record<string, BlockState>): (dx: number, dy: number, dz: number) => BlockState | null {
  return (dx, dy, dz) => neighbors[`${String(dx)},${String(dy)},${String(dz)}`] ?? null;
}

const solid = (state: BlockState): boolean => state.name === 'stone';

void test('legacy fences connect to fences, gates and solid blocks', () => {
  const fence: BlockState = { name: 'oak_fence', properties: { north: 'false', south: 'false', east: 'false', west: 'false' } };
  const derived = deriveLegacyState(fence, lookup({
    '1,0,0': { name: 'oak_fence', properties: {} },
    '0,0,-1': { name: 'stone', properties: {} },
    '-1,0,0': { name: 'oak_fence_gate', properties: { facing: 'north' } },
  }), solid);
  assert.deepEqual(derived?.properties, { north: 'true', south: 'false', east: 'true', west: 'true' });
});

void test('legacy stairs take inner and outer shapes from their neighbours', () => {
  const stairs = (facing: string): BlockState => ({ name: 'oak_stairs', properties: { facing, half: 'bottom', shape: 'straight' } });
  const outer = deriveLegacyState(stairs('north'), lookup({ '0,0,-1': stairs('west') }), solid);
  assert.equal(outer?.properties['shape'], 'outer_left');
  const inner = deriveLegacyState(stairs('north'), lookup({ '0,0,1': stairs('east') }), solid);
  assert.equal(inner?.properties['shape'], 'inner_right');
  assert.equal(deriveLegacyState(stairs('north'), lookup({}), solid), null);
});

void test('legacy door halves share facing and hinge', () => {
  const upper: BlockState = { name: 'oak_door', properties: { half: 'upper', hinge: 'right' } };
  const lower: BlockState = { name: 'oak_door', properties: { half: 'lower', facing: 'south', open: 'true' } };
  assert.deepEqual(deriveLegacyState(upper, lookup({ '0,-1,0': lower }), solid)?.properties, { half: 'upper', hinge: 'right', facing: 'south', open: 'true' });
  assert.equal(deriveLegacyState(lower, lookup({ '0,1,0': upper }), solid)?.properties['hinge'], 'right');
});

void test('legacy panes connect to glass and panes', () => {
  const pane: BlockState = { name: 'glass_pane', properties: {} };
  const derived = deriveLegacyState(pane, lookup({ '1,0,0': { name: 'glass', properties: {} }, '0,0,1': { name: 'glass_pane', properties: {} } }), solid);
  assert.deepEqual(derived?.properties, { north: 'false', south: 'true', east: 'true', west: 'false' });
});
