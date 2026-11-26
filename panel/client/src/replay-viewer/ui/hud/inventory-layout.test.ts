import test from 'node:test';
import assert from 'node:assert/strict';
import type { ReplayPlayerInfo } from '../../engine/ReplayPlayer';
import { inventoryContents, sameInventoryContents } from './inventory-layout.ts';

function holder(overrides: Partial<ReplayPlayerInfo>): ReplayPlayerInfo {
  return {
    entityId: 7,
    uuid: 'a',
    name: 'Armored',
    skinHash: null,
    slim: false,
    isTarget: true,
    present: true,
    health: 20,
    maxHealth: 20,
    absorption: 0,
    food: 20,
    saturation: 5,
    armor: 0,
    xpLevel: 0,
    xpProgress: 0,
    selectedSlot: 0,
    inventory: [{ slot: 9, item: 'minecraft:diamond', count: 3, enchanted: false }],
    mainHand: null,
    offhand: null,
    armorSlots: { head: null, chest: null, legs: null, feet: null },
    effects: [],
    gamemode: 'survival',
    hardcore: false,
    ...overrides,
  };
}

await test('inventory contents change only with what the screen draws', () => {
  const before = inventoryContents(holder({}), true);
  assert.equal(sameInventoryContents(before, inventoryContents(holder({ health: 3, food: 1 }), true)), true);
  assert.equal(sameInventoryContents(before, inventoryContents(holder({ inventory: [{ slot: 9, item: 'minecraft:diamond', count: 4, enchanted: false }] }), true)), false);
  assert.equal(sameInventoryContents(before, inventoryContents(holder({ offhand: { slot: 40, item: 'minecraft:shield', count: 1, enchanted: false } }), true)), false);
  assert.equal(sameInventoryContents(before, inventoryContents(holder({}), false)), false);
});
