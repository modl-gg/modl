import test from 'node:test';
import assert from 'node:assert/strict';

import {
  STALE_CHUNK_RELOAD_WINDOW_MS,
  claimStaleChunkReload,
  isChunkLoadError,
  type ReloadMarkStore,
} from './stale-chunk-recovery.ts';

function memoryStore(initial: string | null = null): ReloadMarkStore & { value: () => string | null } {
  let stored = initial;
  return {
    read: () => stored,
    write: (value) => { stored = value; },
    value: () => stored,
  };
}

await test('dynamic import failures of every browser count as chunk load errors', () => {
  assert.ok(isChunkLoadError(new TypeError('Failed to fetch dynamically imported module: https://panel/assets/home-abc.js')));
  assert.ok(isChunkLoadError(new TypeError('error loading dynamically imported module: https://panel/assets/home-abc.js')));
  assert.ok(isChunkLoadError(new TypeError('Importing a module script failed.')));
  assert.ok(isChunkLoadError(new Error('Unable to preload CSS for /assets/home-abc.css')));
});

await test('other errors are not chunk load errors', () => {
  assert.equal(isChunkLoadError(new TypeError('Cannot read properties of undefined')), false);
  assert.equal(isChunkLoadError('Failed to fetch dynamically imported module'), false);
  assert.equal(isChunkLoadError(null), false);
});

await test('the first chunk failure reloads and records the time', () => {
  const store = memoryStore();
  assert.equal(claimStaleChunkReload(store, 1_000), true);
  assert.equal(store.value(), '1000');
});

await test('a second failure within the window does not reload again', () => {
  const store = memoryStore();
  claimStaleChunkReload(store, 1_000);
  assert.equal(claimStaleChunkReload(store, 1_000 + STALE_CHUNK_RELOAD_WINDOW_MS - 1), false);
});

await test('a failure after the window reloads again', () => {
  const store = memoryStore('1000');
  assert.equal(claimStaleChunkReload(store, 1_000 + STALE_CHUNK_RELOAD_WINDOW_MS), true);
});

await test('an unreadable mark or a mark from the future still allows one reload', () => {
  assert.equal(claimStaleChunkReload(memoryStore('garbage'), 5_000), true);
  assert.equal(claimStaleChunkReload(memoryStore('9000'), 5_000), true);
});

await test('without storage the page never reloads by itself', () => {
  assert.equal(claimStaleChunkReload(null, 1_000), false);
});
