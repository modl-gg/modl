import test from 'node:test';
import assert from 'node:assert/strict';
import { armorPlacements, experienceProgressWidth, foodPlacements, heartLayout, hotbarSlotX, hudAnchors } from './hud-layout.ts';

await test('full health draws ten containers and ten full hearts in one row', () => {
  const layout = heartLayout({ health: 20, maxHealth: 20, absorption: 0, hardcore: false, kind: 'normal' }, 100, 200);
  assert.equal(layout.rows, 1);
  assert.equal(layout.placements.filter((placement) => placement.sprites[0] === 'hud/heart/container').length, 10);
  assert.equal(layout.placements.filter((placement) => placement.sprites[0] === 'hud/heart/full').length, 10);
});

await test('odd health ends in a half heart like the game', () => {
  const layout = heartLayout({ health: 7, maxHealth: 20, absorption: 0, hardcore: false, kind: 'normal' }, 0, 0);
  const fills = layout.placements.filter((placement) => placement.sprites[0] !== 'hud/heart/container');
  assert.deepEqual(fills.map((placement) => placement.sprites[0]).sort(), ['hud/heart/full', 'hud/heart/full', 'hud/heart/full', 'hud/heart/half']);
  assert.equal(fills.find((placement) => placement.sprites[0] === 'hud/heart/half')?.x, 24);
});

await test('absorption hearts continue on a second row with tighter spacing', () => {
  const layout = heartLayout({ health: 20, maxHealth: 20, absorption: 6, hardcore: false, kind: 'normal' }, 0, 100);
  assert.equal(layout.rows, 2);
  assert.equal(layout.rowHeight, 10);
  const absorbing = layout.placements.filter((placement) => placement.sprites[0] === 'hud/heart/absorbing_full');
  assert.equal(absorbing.length, 3);
  assert.ok(absorbing.every((placement) => placement.y === 90));
});

await test('poisoned hardcore hearts fall back to plainer sprites', () => {
  const layout = heartLayout({ health: 2, maxHealth: 20, absorption: 0, hardcore: true, kind: 'poisoned' }, 0, 0);
  const heart = layout.placements.find((placement) => placement.sprites[0]?.includes('poisoned'));
  assert.deepEqual(heart?.sprites, ['hud/heart/poisoned_hardcore_full', 'hud/heart/poisoned_full', 'hud/heart/full']);
  assert.deepEqual(layout.placements[0]?.sprites, ['hud/heart/container_hardcore', 'hud/heart/container']);
});

await test('food fills from the right and armor stays hidden at zero', () => {
  const food = foodPlacements(17, false, 200, 0);
  assert.equal(food.filter((placement) => placement.sprites[0] === 'hud/food_full').length, 8);
  assert.equal(food.find((placement) => placement.sprites[0] === 'hud/food_half')?.x, 200 - 8 * 8 - 9);
  assert.equal(armorPlacements(0, 0, 0).length, 0);
  assert.equal(armorPlacements(5, 0, 0).filter((placement) => placement.sprites[0] === 'hud/armor_half').length, 1);
});

await test('hotbar geometry matches the vanilla layout', () => {
  const anchors = hudAnchors(400, 300);
  assert.equal(anchors.hotbarLeft, 109);
  assert.equal(hotbarSlotX(anchors, 0), 200 - 88);
  assert.equal(hotbarSlotX(anchors, 8), 200 - 88 + 160);
  assert.equal(experienceProgressWidth(1), 183);
  assert.equal(experienceProgressWidth(0.5), 91);
});
