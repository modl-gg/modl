import test from 'node:test';
import assert from 'node:assert/strict';
import { isPublicPage, publicPageOf } from './routes.ts';

await test('every public page is recognized by its path', () => {
  assert.equal(publicPageOf('/'), 'home');
  assert.equal(publicPageOf('/knowledgebase'), 'knowledgebase');
  assert.equal(publicPageOf('/article/rules'), 'article');
  assert.equal(publicPageOf('/appeal'), 'appeal');
  assert.equal(publicPageOf('/submit-ticket/support'), 'submit-ticket');
  assert.equal(publicPageOf('/ticket/ABC-123'), 'ticket');
  assert.equal(publicPageOf('/upload-evidence/token'), 'upload-evidence');
});

await test('staff, sign-in and setup pages are never public pages', () => {
  for (const path of ['/panel', '/panel/settings/theme', '/auth', '/panel/auth', '/setup', '/verify/x', '/replay', '/reset-password', '/accept-invitation']) {
    assert.equal(publicPageOf(path), null, path);
  }
});

await test('evidence upload uses its own upload flow', () => {
  assert.equal(isPublicPage('/upload-evidence/token'), false);
  assert.equal(isPublicPage('/appeal'), true);
});
