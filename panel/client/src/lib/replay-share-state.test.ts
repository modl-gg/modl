import test from 'node:test';
import assert from 'node:assert/strict';
import { isShareActive, isShareRevoked } from './replay-share-state.ts';

const share = (id: string, revokedAt: number | null, expiresAt: number | null = null) => ({ id, revokedAt, expiresAt });

await test('a share is active until it is revoked or expires', () => {
  assert.equal(isShareActive(share('a', null), 10), true);
  assert.equal(isShareActive(share('a', null, 11), 10), true);
  assert.equal(isShareActive(share('a', null, 10), 10), false);
  assert.equal(isShareActive(share('a', 5), 10), false);
});

await test('a share counts as revoked only once the list says so', () => {
  assert.equal(isShareRevoked('a', undefined), false);
  assert.equal(isShareRevoked('a', []), false);
  assert.equal(isShareRevoked('a', [share('a', null)]), false);
  assert.equal(isShareRevoked('a', [share('b', 5), share('a', null)]), false);
  assert.equal(isShareRevoked('a', [share('b', null), share('a', 5)]), true);
});
