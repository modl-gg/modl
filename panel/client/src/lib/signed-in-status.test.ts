import test from 'node:test';
import assert from 'node:assert/strict';

import { signedInStatusOf } from './signed-in-status.ts';

const USER = { id: 'staff-1' };

await test('only a resolved empty profile means signed out', () => {
  assert.equal(signedInStatusOf({ data: null, isError: false }), 'signed-out');
  assert.equal(signedInStatusOf({ data: null, isError: true }), 'signed-out');
});

await test('a failed profile request without data is unavailable, never signed out', () => {
  assert.equal(signedInStatusOf({ data: undefined, isError: true }), 'unavailable');
});

await test('a loaded profile stays signed in when a later refetch fails', () => {
  assert.equal(signedInStatusOf({ data: USER, isError: false }), 'signed-in');
  assert.equal(signedInStatusOf({ data: USER, isError: true }), 'signed-in');
});

await test('a profile request still running is loading', () => {
  assert.equal(signedInStatusOf({ data: undefined, isError: false }), 'loading');
});
