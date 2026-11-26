import test from 'node:test';
import assert from 'node:assert/strict';

import { createNavigationGuard, pathOf } from './navigation-guard.ts';

const CURRENT = 'https://panel.example.com/panel/settings/profile';
const PATH = '/panel/settings/profile';

await test('navigation passes while nothing is blocking', () => {
  const guard = createNavigationGuard();
  assert.equal(guard.intercept('/panel/tickets', CURRENT), false);
  assert.equal(guard.restoreTarget('https://panel.example.com/panel/tickets'), null);
});

await test('a dirty page blocks navigation elsewhere and reports each attempt', () => {
  const guard = createNavigationGuard();
  let blocked = 0;
  const release = guard.activate({ path: PATH, onBlocked: () => { blocked += 1; } });
  assert.equal(guard.intercept('/panel/tickets', CURRENT), true);
  assert.equal(guard.intercept('/panel/settings/security', CURRENT), true);
  assert.equal(blocked, 2);
  release();
  assert.equal(guard.intercept('/panel/tickets', CURRENT), false);
  assert.equal(blocked, 2);
});

await test('staying on the same page is never blocked, whatever the query', () => {
  const guard = createNavigationGuard();
  let blocked = 0;
  guard.activate({ path: PATH, onBlocked: () => { blocked += 1; } });
  assert.equal(guard.intercept('/panel/settings/profile', CURRENT), false);
  assert.equal(guard.intercept('/panel/settings/profile?player=abc', CURRENT), false);
  assert.equal(guard.intercept(CURRENT, CURRENT), false);
  assert.equal(blocked, 0);
});

await test('a history step away from a dirty page is undone to the page path', () => {
  const guard = createNavigationGuard();
  let blocked = 0;
  guard.activate({ path: PATH, onBlocked: () => { blocked += 1; } });
  assert.equal(guard.restoreTarget(`${CURRENT}?player=abc`), null);
  assert.deepEqual(guard.restoreTarget('https://panel.example.com/panel/settings/security'), { path: PATH, state: null });
  assert.equal(blocked, 1);
});

await test('a history step away is undone to the page with its query and history state', () => {
  const guard = createNavigationGuard();
  guard.activate({ path: `${PATH}?replay=r1`, state: { opened: true }, onBlocked: () => undefined });
  assert.equal(guard.restoreTarget(`${CURRENT}?replay=r2`), null);
  assert.deepEqual(guard.restoreTarget('https://panel.example.com/panel/tickets'), { path: `${PATH}?replay=r1`, state: { opened: true } });
});

await test('releasing either of two blocks keeps the other active', () => {
  const guard = createNavigationGuard();
  const releaseFirst = guard.activate({ path: PATH, onBlocked: () => undefined });
  const releaseSecond = guard.activate({ path: PATH, onBlocked: () => undefined });
  releaseSecond();
  assert.equal(guard.intercept('/panel/tickets', CURRENT), true);
  releaseSecond();
  assert.equal(guard.intercept('/panel/tickets', CURRENT), true);
  releaseFirst();
  assert.equal(guard.intercept('/panel/tickets', CURRENT), false);
});

await test('paths resolve relative to the current address', () => {
  assert.equal(pathOf('security', CURRENT), '/panel/settings/security');
  assert.equal(pathOf('/panel/settings/profile#top', CURRENT), PATH);
});
