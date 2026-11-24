import test from 'node:test';
import assert from 'node:assert/strict';

import { canEditRole, editableRoleOrder } from './role-order.ts';

const ROLES = [
  { id: 'admin', order: 2 },
  { id: 'senior', order: 3 },
  { id: 'helper', order: 5 },
  { id: 'moderator', order: 4 },
];

await test('a super admin renumbers every role in the new order', () => {
  assert.deepEqual(editableRoleOrder(ROLES, { superAdmin: true, order: 1 }), [
    { id: 'admin', order: 1 },
    { id: 'senior', order: 2 },
    { id: 'helper', order: 3 },
    { id: 'moderator', order: 4 },
  ]);
});

await test('other staff renumber only the roles below their own, right after it', () => {
  assert.deepEqual(editableRoleOrder(ROLES, { superAdmin: false, order: 2 }), [
    { id: 'senior', order: 3 },
    { id: 'helper', order: 4 },
    { id: 'moderator', order: 5 },
  ]);
  assert.deepEqual(editableRoleOrder(ROLES, { superAdmin: false, order: 5 }), []);
});

await test('only roles below the editor can be edited, except by a super admin', () => {
  assert.equal(canEditRole({ id: 'senior', order: 3 }, { superAdmin: false, order: 2 }), true);
  assert.equal(canEditRole({ id: 'admin', order: 2 }, { superAdmin: false, order: 2 }), false);
  assert.equal(canEditRole({ id: 'admin', order: 2 }, { superAdmin: true, order: 9 }), true);
});
