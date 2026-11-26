import test from 'node:test';
import assert from 'node:assert/strict';
import type { ReplayPlayerInfo } from '../../engine/ReplayPlayer';
import { playerHudSubject } from './hud-visibility.ts';

const viewed: ReplayPlayerInfo = {
  entityId: 7,
  uuid: '069a79f4-44e9-4726-a5be-fca90e38aaf5',
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
  inventory: [],
  mainHand: null,
  offhand: null,
  armorSlots: { head: null, chest: null, legs: null, feet: null },
  effects: [],
  gamemode: 'survival',
  hardcore: false,
};

await test('the survival HUD shows while the camera is on a player', () => {
  assert.equal(playerHudSubject('first-person', viewed), viewed);
  assert.equal(playerHudSubject('follow', viewed), viewed);
});

await test('the free camera shows no HUD', () => {
  assert.equal(playerHudSubject('free', viewed), null);
});

await test('a camera without a viewed player shows no HUD', () => {
  assert.equal(playerHudSubject('first-person', null), null);
  assert.equal(playerHudSubject('follow', null), null);
});
