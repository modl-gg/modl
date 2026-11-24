import test from 'node:test';
import assert from 'node:assert/strict';

import {
  SETTINGS_DATA_CONSUMERS,
  SETTINGS_GROUPS,
  SETTINGS_PAGE_IDS,
  accessibleSettingsGroups,
  canAccessSettingsPage,
  canModifySettingsPage,
  isSettingsPageId,
  legacySettingsPage,
  resolveSettingsPage,
  settingsPagePath,
  shouldLoadSettingsData,
  type SettingsAccess,
  type SettingsData,
} from './settings-access.ts';

const grants = (isSuperAdmin: boolean, ...permissions: string[]): SettingsAccess => ({
  isSuperAdmin,
  hasPermission: (permissionId) => permissions.includes(permissionId),
});

const SUPER_ADMIN = grants(true, 'admin.settings.view');
const SETTINGS_ADMIN = grants(false, 'admin.settings.view');
const NO_SETTINGS = grants(false, 'ticket.view.all');

const visiblePages = (access: SettingsAccess) => accessibleSettingsGroups(access).flatMap((group) => group.pages);
const visibleGroups = (access: SettingsAccess) => accessibleSettingsGroups(access).map((group) => group.id);

await test('the owner sees every group and page in the agreed order', () => {
  assert.deepEqual(accessibleSettingsGroups(SUPER_ADMIN), [
    { id: 'account', pages: ['profile', 'security'] },
    { id: 'general', pages: ['panel', 'minecraft-servers', 'email', 'webhooks'] },
    { id: 'staff', pages: ['members', 'roles', 'sign-in'] },
    { id: 'moderation', pages: ['punishment-types', 'escalation', 'player-addresses'] },
    { id: 'tickets', pages: ['forms', 'labels', 'quick-replies', 'ai-moderation'] },
    { id: 'public-site', pages: ['homepage', 'knowledgebase', 'theme', 'custom-css'] },
    { id: 'system', pages: ['storage', 'database', 'data-retention', 'updates', 'status'] },
  ]);
});

await test('every page belongs to exactly one group', () => {
  const grouped = SETTINGS_GROUPS.flatMap((group) => group.pages.map((page) => page.id));
  assert.deepEqual([...grouped].sort(), [...SETTINGS_PAGE_IDS].sort());
  assert.equal(new Set(grouped).size, grouped.length);
});

await test('owner-only pages are hidden from settings admins', () => {
  assert.deepEqual(visiblePages(SETTINGS_ADMIN), [
    'profile',
    'security',
    'webhooks',
    'punishment-types',
    'escalation',
    'forms',
    'labels',
    'quick-replies',
    'ai-moderation',
    'homepage',
    'knowledgebase',
    'theme',
    'storage',
    'data-retention',
  ]);
});

await test('groups without an accessible page are hidden', () => {
  assert.deepEqual(visibleGroups(NO_SETTINGS), ['account']);
  assert.deepEqual(visibleGroups(grants(false, 'admin.settings.view.storage')), ['account', 'system']);
  assert.deepEqual(visiblePages(grants(false, 'admin.settings.view.storage')), ['profile', 'security', 'storage']);
});

await test('punishment and content viewers see only their pages', () => {
  assert.deepEqual(visiblePages(grants(false, 'admin.settings.view.punishments')), ['profile', 'security', 'punishment-types', 'escalation']);
  assert.deepEqual(visiblePages(grants(false, 'admin.settings.view.content')), ['profile', 'security', 'homepage', 'knowledgebase', 'theme']);
});

await test('the staff pages open for their own sub-permission', () => {
  assert.deepEqual(visiblePages(grants(false, 'admin.staff.manage.members')), ['profile', 'security', 'members']);
  assert.deepEqual(visiblePages(grants(false, 'admin.staff.manage.roles')), ['profile', 'security', 'roles']);
  assert.equal(canAccessSettingsPage('sign-in', grants(false, 'admin.staff.manage.members', 'admin.staff.manage.roles')), false);
});

await test('viewing a page does not allow changing it', () => {
  assert.equal(canModifySettingsPage('labels', SETTINGS_ADMIN), false);
  assert.equal(canModifySettingsPage('punishment-types', grants(false, 'admin.settings.view.punishments')), false);
  assert.equal(canModifySettingsPage('knowledgebase', grants(false, 'admin.settings.view.content')), false);
});

await test('each page is changed with its own modify permission', () => {
  assert.equal(canModifySettingsPage('labels', grants(false, 'admin.settings.view', 'admin.settings.modify')), true);
  assert.equal(canModifySettingsPage('punishment-types', grants(false, 'admin.settings.view.punishments', 'admin.settings.modify.punishments')), true);
  assert.equal(canModifySettingsPage('homepage', grants(false, 'admin.settings.view.content', 'admin.settings.modify.content')), true);
  assert.equal(canModifySettingsPage('homepage', grants(false, 'admin.settings.modify.punishments')), false);
  assert.equal(canModifySettingsPage('members', grants(false, 'admin.staff.manage.members')), true);
  assert.equal(canModifySettingsPage('data-retention', grants(false, 'admin.settings.modify')), false);
  assert.ok(SETTINGS_PAGE_IDS.every((pageId) => canModifySettingsPage(pageId, SUPER_ADMIN)));
});

await test('pages resolve from the URL and fall back to the profile', () => {
  assert.deepEqual(resolveSettingsPage(undefined, NO_SETTINGS), { kind: 'redirect', pageId: 'profile' });
  assert.deepEqual(resolveSettingsPage('profile', NO_SETTINGS), { kind: 'page', pageId: 'profile' });
  assert.deepEqual(resolveSettingsPage('webhooks', SETTINGS_ADMIN), { kind: 'page', pageId: 'webhooks' });
  assert.deepEqual(resolveSettingsPage('email', SETTINGS_ADMIN), { kind: 'redirect', pageId: 'profile' });
  assert.deepEqual(resolveSettingsPage('not-a-page', SUPER_ADMIN), { kind: 'redirect', pageId: 'profile' });
  assert.deepEqual(resolveSettingsPage('toString', SUPER_ADMIN), { kind: 'redirect', pageId: 'profile' });
  assert.equal(isSettingsPageId('constructor'), false);
});

await test('every page has its own address', () => {
  assert.equal(settingsPagePath('minecraft-servers'), '/panel/settings/minecraft-servers');
  assert.equal(new Set(SETTINGS_PAGE_IDS.map(settingsPagePath)).size, SETTINGS_PAGE_IDS.length);
});

await test('old category and section links map to the new pages', () => {
  const cases: [string, string | null][] = [
    ['', null],
    ['?category=general&sub=server-config', 'panel'],
    ['?category=general&sub=api-key', 'minecraft-servers'],
    ['?category=general&sub=sign-in', 'sign-in'],
    ['?category=general&sub=email', 'email'],
    ['?category=general&sub=webhooks', 'webhooks'],
    ['?category=general&sub=data-retention', 'data-retention'],
    ['?category=general&sub=storage', 'storage'],
    ['?category=general&sub=player-addresses', 'player-addresses'],
    ['?category=general&sub=updates', 'updates'],
    ['?category=general&sub=status', 'status'],
    ['?category=punishment&sub=thresholds', 'escalation'],
    ['?category=punishment&sub=types', 'punishment-types'],
    ['?category=tickets&sub=quick-responses', 'quick-replies'],
    ['?category=tickets&sub=label-management', 'labels'],
    ['?category=tickets&sub=ticket-forms', 'forms'],
    ['?category=tickets&sub=ai-moderation', 'ai-moderation'],
    ['?category=tags&sub=ticket-forms', 'forms'],
    ['?category=staff&sub=staff-management', 'members'],
    ['?category=staff&sub=roles-permissions', 'roles'],
    ['?category=knowledgebase&sub=knowledgebase-articles', 'knowledgebase'],
    ['?category=homepage&sub=homepage-cards', 'homepage'],
    ['?tab=general&section=webhooks,email', 'webhooks'],
    ['?category=staff', 'members'],
    ['?category=account', 'profile'],
    ['?category=unknown&sub=x', 'profile'],
    ['?category=general&sub=unknown', 'panel'],
    ['?category=constructor&sub=toString', 'profile'],
    ['?category=general&sub=hasOwnProperty', 'panel'],
  ];
  for (const [search, expected] of cases) {
    assert.equal(legacySettingsPage(search), expected, search);
  }
});

const HELPER = grants(false, 'punishment.view', 'player.note', 'player.warn', 'ticket.view.all', 'ticket.view.all.notes', 'ticket.reply.all', 'ticket.reply.all.notes', 'appeal.modify');
const ADMIN = grants(
  false,
  'admin.settings.view', 'admin.settings.view.punishments', 'admin.settings.view.content',
  'admin.settings.view.storage', 'ticket.view.all', 'punishment.view'
);
const isSettingsData = (key: string): key is SettingsData => Object.hasOwn(SETTINGS_DATA_CONSUMERS, key);
const SETTINGS_DATA: SettingsData[] = Object.keys(SETTINGS_DATA_CONSUMERS).filter(isSettingsData);

const loadedData = (access: SettingsAccess) => SETTINGS_DATA.filter((data) => shouldLoadSettingsData(data, access));

await test('ticket handlers load no settings data', () => {
  assert.deepEqual(loadedData(HELPER), []);
});

await test('settings viewers load the data of every page they can open', () => {
  assert.deepEqual(loadedData(ADMIN), SETTINGS_DATA);
  assert.deepEqual(loadedData(SUPER_ADMIN), SETTINGS_DATA);
});

await test('punishment configuration viewers load only punishment configuration', () => {
  assert.deepEqual(loadedData(grants(false, 'admin.settings.view.punishments')), ['punishmentTypes', 'statusThresholds']);
});
