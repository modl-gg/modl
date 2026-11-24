export const PERMISSIONS = {
  ADMIN_SETTINGS_VIEW: 'admin.settings.view',
  ADMIN_SETTINGS_VIEW_PUNISHMENTS: 'admin.settings.view.punishments',
  ADMIN_SETTINGS_VIEW_CONTENT: 'admin.settings.view.content',
  ADMIN_SETTINGS_MODIFY: 'admin.settings.modify',
  ADMIN_SETTINGS_MODIFY_PUNISHMENTS: 'admin.settings.modify.punishments',
  ADMIN_SETTINGS_MODIFY_CONTENT: 'admin.settings.modify.content',
  ADMIN_SETTINGS_VIEW_STORAGE: 'admin.settings.view.storage',
  ADMIN_SETTINGS_MODIFY_STORAGE: 'admin.settings.modify.storage',
  ADMIN_STAFF_MANAGE: 'admin.staff.manage',
  ADMIN_STAFF_MANAGE_MEMBERS: 'admin.staff.manage.members',
  ADMIN_STAFF_MANAGE_ROLES: 'admin.staff.manage.roles',
  ADMIN_AUDIT_VIEW: 'admin.audit.view',
  ADMIN_AUDIT_VIEW_DASHBOARD: 'admin.audit.view.dashboard',
  ADMIN_AUDIT_VIEW_ANALYTICS: 'admin.audit.view.analytics',
  ADMIN_AUDIT_VIEW_LOGS: 'admin.audit.view.logs',

  PUNISHMENT_VIEW: 'punishment.view',
  PUNISHMENT_MODIFY: 'punishment.modify',
  PUNISHMENT_MODIFY_PARDON: 'punishment.modify.pardon',
  PUNISHMENT_MODIFY_DURATION: 'punishment.modify.duration',
  PUNISHMENT_MODIFY_NOTE: 'punishment.modify.note',
  PUNISHMENT_MODIFY_EVIDENCE: 'punishment.modify.evidence',
  PUNISHMENT_MODIFY_OPTIONS: 'punishment.modify.options',
  PUNISHMENT_MODIFY_TICKETS: 'punishment.modify.tickets',

  PLAYER_NOTE: 'player.note',
  PLAYER_WARN: 'player.warn',

  APPEAL_MODIFY: 'appeal.modify',

  STAFF_CHAT_TOGGLE: 'staff.chat.toggle',
  STAFF_CHAT_CLEAR: 'staff.chat.clear',
  STAFF_CHAT_SLOW: 'staff.chat.slow',
  STAFF_MODACTIONS: 'staff.modactions',
  STAFF_INTERCEPT: 'staff.intercept',
  STAFF_CHATLOGS: 'staff.chatlogs',
  STAFF_COMMANDLOGS: 'staff.commandlogs',

  TICKET_VIEW_ALL: 'ticket.view.all',
  TICKET_VIEW_ALL_NOTES: 'ticket.view.all.notes',
  TICKET_REPLY_ALL: 'ticket.reply.all',
  TICKET_REPLY_ALL_NOTES: 'ticket.reply.all.notes',
  TICKET_CLOSE_ALL: 'ticket.close.all',
  TICKET_MANAGE: 'ticket.manage',
  TICKET_MANAGE_TAGS: 'ticket.manage.tags',
  TICKET_MANAGE_HIDE: 'ticket.manage.hide',
} as const;

export const PUNISHMENT_APPLY_PREFIX = 'punishment.apply.';

export function canViewPlayers(granted: readonly string[]): boolean {
  return granted.some((permission) => permission === PERMISSIONS.PUNISHMENT_VIEW
    || permission === PERMISSIONS.PUNISHMENT_MODIFY
    || permission.startsWith(PUNISHMENT_APPLY_PREFIX));
}

export function punishmentApplyPermission(punishmentTypeOrdinal: number): string {
  return `${PUNISHMENT_APPLY_PREFIX}${punishmentTypeOrdinal.toString()}`;
}

export interface TicketNoteAccess {
  readonly canView: boolean;
  readonly canAdd: boolean;
}

export function ticketNoteAccess(granted: readonly string[]): TicketNoteAccess {
  return {
    canView: granted.includes(PERMISSIONS.TICKET_VIEW_ALL_NOTES),
    canAdd: granted.includes(PERMISSIONS.TICKET_REPLY_ALL_NOTES),
  };
}
