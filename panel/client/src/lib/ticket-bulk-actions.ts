import { PERMISSIONS } from './permissions.ts';
import type { PermissionCheck } from './punishment-actions.ts';

export interface BulkTicketActions {
  readonly changeStatus: boolean;
  readonly label: boolean;
  readonly assign: boolean;
}

export function bulkTicketActions(has: PermissionCheck): BulkTicketActions {
  const mayBulkUpdate = has(PERMISSIONS.TICKET_CLOSE_ALL);
  return {
    changeStatus: mayBulkUpdate,
    label: mayBulkUpdate && has(PERMISSIONS.TICKET_MANAGE_TAGS),
    assign: mayBulkUpdate,
  };
}
