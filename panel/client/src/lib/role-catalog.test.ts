import test from 'node:test';
import assert from 'node:assert/strict';

import { indexRoleCatalog } from './role-catalog.ts';

interface TestPermission {
  id: string;
  parentId: string | null;
  category: 'admin' | 'punishment' | 'ticket';
}

const CATALOG: readonly TestPermission[] = [
  { id: 'ticket.view', parentId: null, category: 'ticket' },
  { id: 'ticket.view.notes', parentId: 'ticket.view', category: 'ticket' },
  { id: 'admin.settings', parentId: null, category: 'admin' },
];

await test('categories follow the given order, skip empty ones and list their top-level permissions', () => {
  const index = indexRoleCatalog(CATALOG, ['admin', 'punishment', 'ticket']);
  assert.deepEqual(index.categories.map((entry) => entry.category), ['admin', 'ticket']);
  const ticket = index.categories.find((entry) => entry.category === 'ticket');
  assert.ok(ticket);
  assert.deepEqual(ticket.permissions.map((permission) => permission.id), ['ticket.view', 'ticket.view.notes']);
  assert.deepEqual(ticket.parents.map((permission) => permission.id), ['ticket.view']);
});

await test('the index answers grants through catalog parents', () => {
  const index = indexRoleCatalog(CATALOG, ['ticket']);
  assert.equal(index.grants(['ticket.view'], 'ticket.view.notes'), true);
  assert.equal(index.grants(['ticket.view.notes'], 'ticket.view'), false);
});
