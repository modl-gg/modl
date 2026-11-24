import test from 'node:test';
import assert from 'node:assert/strict';

import type { GeneralSettingsSnapshot } from '@/hooks/data/settings';
import { isPublicUrlValid, panelIdentityChanges, panelIdentityOf, serverIdentityChanges, serverIdentityOf } from './settings-identity.ts';

const SNAPSHOT: GeneralSettingsSnapshot = {
  serverDisplayName: 'Server',
  defaultLanguage: '',
  homepageIconUrl: '',
  panelIconUrl: '',
  publicUrl: '',
  publicUrlManagedByConfig: false,
  serverNameManaged: false,
  version: 3,
};

await test('an unset default language reads as the panel default', () => {
  assert.equal(serverIdentityOf(SNAPSHOT).defaultLanguage, 'en');
});

await test('only changed values are sent and the name is trimmed', () => {
  const identity = serverIdentityOf(SNAPSHOT);
  assert.deepEqual(serverIdentityChanges({ ...identity, serverDisplayName: ' Server ' }, SNAPSHOT), {});
  assert.deepEqual(serverIdentityChanges({ ...identity, serverDisplayName: ' New ', panelIconUrl: '/icon' }, SNAPSHOT), {
    serverDisplayName: 'New',
    panelIconUrl: '/icon',
  });
  assert.deepEqual(serverIdentityChanges({ ...identity, serverDisplayName: 'New' }, { ...SNAPSHOT, serverNameManaged: true }), {});
  const padded = { ...SNAPSHOT, serverDisplayName: ' Server ' };
  assert.deepEqual(serverIdentityChanges(serverIdentityOf(padded), padded), {});
});

await test('an empty stored panel URL stays valid until it is edited, and a valid URL is sent as its origin', () => {
  const identity = panelIdentityOf(SNAPSHOT);
  assert.equal(isPublicUrlValid(identity, SNAPSHOT), true);
  assert.equal(isPublicUrlValid({ ...identity, publicUrl: 'not a url' }, SNAPSHOT), false);
  assert.deepEqual(panelIdentityChanges(identity, SNAPSHOT), {});
  assert.deepEqual(panelIdentityChanges({ ...identity, publicUrl: 'https://panel.example.com/' }, SNAPSHOT), { publicUrl: 'https://panel.example.com' });
});
