import test from 'node:test';
import assert from 'node:assert/strict';

import { bulkTicketActions } from './ticket-bulk-actions.ts';

function holding(...granted: string[]) {
  return (permission: string) => granted.includes(permission);
}

await test('a staff member who cannot use the bulk route sees no bulk action', () => {
  assert.deepEqual(bulkTicketActions(holding('ticket.view.all', 'ticket.reply.all', 'ticket.manage.tags')),
    { changeStatus: false, label: false, assign: false });
});

await test('bulk labels also need the tag permission', () => {
  assert.deepEqual(bulkTicketActions(holding('ticket.close.all')), { changeStatus: true, label: false, assign: true });
  assert.deepEqual(bulkTicketActions(holding('ticket.close.all', 'ticket.manage.tags')),
    { changeStatus: true, label: true, assign: true });
});
