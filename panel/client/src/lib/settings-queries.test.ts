import test from 'node:test';
import assert from 'node:assert/strict';

import { isSettingsQueryKey } from './settings-queries.ts';

await test('every settings area is refreshed by a settings invalidation', () => {
  for (const root of [
    '/v1/settings',
    '/v1/public/settings',
    '/v1/panel/settings/general',
    '/v1/panel/settings/storage',
    '/v1/panel/settings/data-retention/limits',
    '/v1/panel/settings/sign-in',
    '/v1/panel/settings/player-addresses',
    '/v1/panel/settings/ticket-labels',
    '/v1/panel/updates',
  ]) {
    assert.equal(isSettingsQueryKey([root]), true, root);
  }
  assert.equal(isSettingsQueryKey(['/v1/panel/settings/ticket-labels', 'labels']), true);
});

await test('other panel data is left alone', () => {
  for (const key of [['/v1/panel/players'], ['/v1/panel/settingsx'], ['userPermissions'], [42], []]) {
    assert.equal(isSettingsQueryKey(key), false, JSON.stringify(key));
  }
});
