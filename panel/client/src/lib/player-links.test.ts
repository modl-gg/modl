import test from 'node:test';
import assert from 'node:assert/strict';

import { focusRequestFor, pagePunishmentFocus, playerOpenTarget, playerPagePath, playerUrlRequestOf, showPlayerParam } from './player-links.ts';

const PLAYER = '0f8c2b1e-3d4a-4b5c-8d9e-0a1b2c3d4e5f';

await test('a phone opens the player page and a wider screen opens a window', () => {
  assert.deepEqual(playerOpenTarget(true, PLAYER), { kind: 'page', path: `/panel/player/${PLAYER}` });
  assert.deepEqual(playerOpenTarget(false, PLAYER, '42'), { kind: 'window' });
});

await test('the player page keeps the punishment to focus and encodes both values', () => {
  assert.equal(playerPagePath(PLAYER, 'A B'), `/panel/player/${PLAYER}?punishment=A+B`);
  assert.equal(playerPagePath('a/b'), '/panel/player/a%2Fb');
});

await test('the address names a player to open, with an optional punishment', () => {
  assert.deepEqual(playerUrlRequestOf(`?player=${PLAYER}&punishment=7`), { playerId: PLAYER, punishmentId: '7' });
  assert.deepEqual(playerUrlRequestOf(`?player=${PLAYER}`), { playerId: PLAYER, punishmentId: '' });
  assert.equal(playerUrlRequestOf('?punishment=7'), null);
  assert.equal(playerUrlRequestOf('?player=%20'), null);
});

await test('the address names the shown player window and drops the punishment it was opened with', () => {
  const params = new URLSearchParams(`?tab=1&player=${PLAYER}&punishment=7`);
  assert.equal(showPlayerParam(params, 'other'), true);
  assert.equal(params.toString(), 'tab=1&player=other');
  assert.equal(showPlayerParam(params, 'other'), false);
  assert.equal(showPlayerParam(params, null), true);
  assert.equal(params.toString(), 'tab=1');
  assert.equal(showPlayerParam(params, null), false);
  const pageParams = new URLSearchParams('?punishment=7');
  assert.equal(showPlayerParam(pageParams, null), false);
  assert.equal(pageParams.toString(), 'punishment=7');
});

await test('a punishment focus request reaches only its punishment', () => {
  assert.equal(pagePunishmentFocus(''), null);
  const focus = pagePunishmentFocus('7');
  assert.equal(focusRequestFor(focus, '7'), 0);
  assert.equal(focusRequestFor(focus, '8'), null);
  assert.equal(focusRequestFor({ punishmentId: '7', request: 3 }, '7'), 3);
  assert.equal(focusRequestFor(null, '7'), null);
});
