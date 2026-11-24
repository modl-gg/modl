import test from 'node:test';
import assert from 'node:assert/strict';

import { roleGrantsFor } from './role-grants.ts';

const CATALOG = [
  { id: 'ticket.view.all', parentId: null },
  { id: 'ticket.view.all.notes', parentId: null },
  { id: 'punishment.modify', parentId: null },
  { id: 'punishment.modify.pardon', parentId: 'punishment.modify' },
];

await test('a parent grants the children the catalog links to it and nothing else', () => {
  const grants = roleGrantsFor(CATALOG);
  assert.equal(grants(['punishment.modify'], 'punishment.modify.pardon'), true);
  assert.equal(grants(['ticket.view.all'], 'ticket.view.all.notes'), false);
  assert.equal(grants(['ticket.view.all.notes'], 'ticket.view.all.notes'), true);
  assert.equal(grants([], 'ticket.view.all'), false);
});

await test('a parent cycle in the catalog ends the walk', () => {
  const grants = roleGrantsFor([{ id: 'a', parentId: 'b' }, { id: 'b', parentId: 'a' }]);
  assert.equal(grants([], 'a'), false);
  assert.equal(grants(['b'], 'a'), true);
});
