export const AUDIT_PUNISHMENT_TYPES_KEY = ['audit-punishment-types'] as const;

export const AUDIT_QUERY_ROOTS = [
  ['staff-performance'],
  ['punishment-analytics'],
  ['player-activity'],
  ['ticket-analytics'],
  ['punishments-list'],
  ['staff-details'],
  AUDIT_PUNISHMENT_TYPES_KEY,
] as const;
