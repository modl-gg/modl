import test from 'node:test';
import assert from 'node:assert/strict';

import { PANEL_SIGN_IN_PATH, readReturnPath, safeReturnPath, signInPathReturningTo } from './return-path.ts';

await test('same origin paths are accepted as return paths with their query', () => {
  assert.equal(safeReturnPath('/verify/abc123'), '/verify/abc123');
  assert.equal(safeReturnPath('/panel/tickets/42?tab=notes#reply'), '/panel/tickets/42?tab=notes#reply');
});

await test('anything that could leave the panel is rejected', () => {
  assert.equal(safeReturnPath('https://evil.example.com'), null);
  assert.equal(safeReturnPath('//evil.example.com/path'), null);
  assert.equal(safeReturnPath('/\\evil.example.com'), null);
  assert.equal(safeReturnPath('javascript:alert(1)'), null);
  assert.equal(safeReturnPath('verify/abc'), null);
  assert.equal(safeReturnPath(''), null);
  assert.equal(safeReturnPath(null), null);
});

await test('the sign-in page is never a return path', () => {
  assert.equal(safeReturnPath(PANEL_SIGN_IN_PATH), null);
});

await test('the return path is read from the next parameter', () => {
  assert.equal(readReturnPath('?next=%2Fverify%2Fabc'), '/verify/abc');
  assert.equal(readReturnPath('?next=https%3A%2F%2Fevil.example.com'), null);
  assert.equal(readReturnPath(''), null);
});

await test('the sign-in path carries the return path unless it is the dashboard', () => {
  assert.equal(signInPathReturningTo(PANEL_SIGN_IN_PATH, '/verify/abc'), '/panel/auth?next=%2Fverify%2Fabc');
  assert.equal(signInPathReturningTo(PANEL_SIGN_IN_PATH, '/panel'), PANEL_SIGN_IN_PATH);
  assert.equal(signInPathReturningTo(PANEL_SIGN_IN_PATH, '//evil.example.com'), PANEL_SIGN_IN_PATH);
});
