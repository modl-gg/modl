import test from 'node:test';
import assert from 'node:assert/strict';

import { PERMISSIONS, canViewPlayers, ticketNoteAccess } from './permissions.ts';

await test('view, modify or any apply permission views players', () => {
  for (const granted of ['punishment.view', 'punishment.modify', 'punishment.apply.7']) {
    assert.equal(canViewPlayers(['ticket.view.all', granted]), true, granted);
  }
});

await test('other permissions do not view players', () => {
  assert.equal(canViewPlayers([]), false);
  assert.equal(canViewPlayers([
    PERMISSIONS.PLAYER_NOTE,
    PERMISSIONS.PLAYER_WARN,
    PERMISSIONS.PUNISHMENT_MODIFY_NOTE,
    PERMISSIONS.PUNISHMENT_MODIFY_PARDON,
    PERMISSIONS.TICKET_VIEW_ALL,
  ]), false);
});

await test('ticket notes need their own permissions, not the ticket view and reply permissions', () => {
  assert.deepEqual(ticketNoteAccess([PERMISSIONS.TICKET_VIEW_ALL, PERMISSIONS.TICKET_REPLY_ALL]), { canView: false, canAdd: false });
  assert.deepEqual(ticketNoteAccess([PERMISSIONS.TICKET_VIEW_ALL, PERMISSIONS.TICKET_VIEW_ALL_NOTES]), { canView: true, canAdd: false });
  assert.deepEqual(ticketNoteAccess([PERMISSIONS.TICKET_REPLY_ALL_NOTES]), { canView: false, canAdd: true });
});
