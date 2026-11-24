import test from 'node:test';
import assert from 'node:assert/strict';

import { roleGrantsFor } from './role-grants.ts';
import { categoryGrant, permissionRuns, toggledCategory, toggledPermission } from './role-permissions.ts';

const CATALOG = [
  { id: 'ticket.view', parentId: null },
  { id: 'ticket.view.notes', parentId: 'ticket.view' },
  { id: 'ticket.reply', parentId: null },
  { id: 'ticket.close', parentId: null },
];

const GRANTS = roleGrantsFor(CATALOG);

const everything = () => true;

await test('leaf permissions are grouped into runs between parents with children', () => {
  const isLeaf = (id: string) => !id.startsWith('parent');
  assert.deepEqual(permissionRuns(['a', 'b', 'parent1', 'c', 'parent2', 'parent3', 'd'], isLeaf), [
    { leaf: true, items: ['a', 'b'] },
    { leaf: false, items: ['parent1'] },
    { leaf: true, items: ['c'] },
    { leaf: false, items: ['parent2', 'parent3'] },
    { leaf: true, items: ['d'] },
  ]);
  assert.deepEqual(permissionRuns([], isLeaf), []);
});

await test('a category is all, some or none of what the editor may grant, counting children granted by a parent', () => {
  assert.equal(categoryGrant(CATALOG, [], GRANTS, everything), 'none');
  assert.equal(categoryGrant(CATALOG, ['ticket.view'], GRANTS, everything), 'some');
  assert.equal(categoryGrant(CATALOG, ['ticket.view', 'ticket.reply', 'ticket.close'], GRANTS, everything), 'all');
  assert.equal(categoryGrant(CATALOG, ['ticket.reply'], GRANTS, (id) => id === 'ticket.reply'), 'all');
  assert.equal(categoryGrant(CATALOG, ['ticket.close'], GRANTS, (id) => id === 'ticket.reply'), 'none');
  assert.equal(categoryGrant(CATALOG, ['ticket.view', 'ticket.reply', 'ticket.close'], GRANTS, () => false), 'all');
  assert.equal(categoryGrant(CATALOG, ['ticket.reply'], GRANTS, () => false), 'some');
});

await test('toggling a category grants everything grantable, or removes it when all is granted, and keeps the rest', () => {
  const onlyReplyAndClose = (id: string) => id === 'ticket.reply' || id === 'ticket.close';
  assert.deepEqual(toggledCategory(CATALOG, ['ticket.view', 'other'], GRANTS, onlyReplyAndClose), ['ticket.view', 'other', 'ticket.reply', 'ticket.close']);
  assert.deepEqual(toggledCategory(CATALOG, ['ticket.view', 'ticket.reply', 'ticket.close'], GRANTS, onlyReplyAndClose), ['ticket.view']);
  assert.deepEqual(new Set(toggledCategory(CATALOG, ['ticket.reply'], GRANTS, everything)), new Set(CATALOG.map((permission) => permission.id)));
});

await test('a permission is granted or revoked alone, so revoking a parent also revokes what it implied', () => {
  assert.deepEqual(toggledPermission('ticket.view', []), ['ticket.view']);
  assert.deepEqual(toggledPermission('ticket.view', ['ticket.view', 'ticket.reply']), ['ticket.reply']);
  assert.deepEqual(toggledPermission('ticket.reply', ['ticket.view']), ['ticket.view', 'ticket.reply']);
});
