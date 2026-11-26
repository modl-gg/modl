import test from 'node:test';
import assert from 'node:assert/strict';
import type { ReplayPlayerInfo } from '../../engine/ReplayPlayer.ts';
import { sameTabRows, tabRows } from './tab-rows.ts';

function info(uuid: string, name: string, overrides: Partial<ReplayPlayerInfo> = {}): ReplayPlayerInfo {
  return {
    entityId: 1,
    uuid,
    name,
    skinHash: null,
    slim: false,
    isTarget: false,
    present: true,
    health: 20,
    maxHealth: 20,
    absorption: null,
    food: 20,
    saturation: 5,
    armor: 0,
    xpLevel: 0,
    xpProgress: 0,
    selectedSlot: 0,
    inventory: [],
    mainHand: null,
    offhand: null,
    armorSlots: { head: null, chest: null, legs: null, feet: null },
    effects: [],
    gamemode: null,
    hardcore: false,
    ...overrides,
  };
}

await test('tab rows list the target first, then present players by name', () => {
  const rows = tabRows([info('c', 'Cara', { present: false }), info('b', 'Bob'), info('a', 'Zed', { isTarget: true, entityId: -1 })]);
  assert.deepEqual(rows.map((row) => row.uuid), ['a', 'b', 'c']);
  assert.equal(rows[0]?.entityId, null);
});

await test('fields that are not drawn never count as a change', () => {
  const before = tabRows([info('a', 'Alex', { food: 20, xpLevel: 1 })]);
  assert.equal(sameTabRows(before, tabRows([info('a', 'Alex', { food: 3, xpLevel: 9, health: 19.5 })])), true);
  assert.equal(sameTabRows(before, tabRows([info('a', 'Alex', { health: 19 })])), false);
  assert.equal(sameTabRows(before, tabRows([info('a', 'Alex', { present: false })])), false);
  assert.equal(sameTabRows(before, []), false);
});
