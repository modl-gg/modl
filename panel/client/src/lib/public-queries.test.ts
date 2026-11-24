import test from 'node:test';
import assert from 'node:assert/strict';

import { isPublicQueryKey, PUBLIC_SETTINGS_QUERY_KEY } from './public-queries.ts';

await test('queries keyed by a public API path are public', () => {
  assert.equal(isPublicQueryKey(PUBLIC_SETTINGS_QUERY_KEY), true);
  assert.equal(isPublicQueryKey(['/v1/public/knowledgebase/articles/rules']), true);
});

await test('panel and unscoped query keys are not public', () => {
  assert.equal(isPublicQueryKey(['/v1/panel/auth/me']), false);
  assert.equal(isPublicQueryKey(['publicHomepageCards']), false);
  assert.equal(isPublicQueryKey([]), false);
});
