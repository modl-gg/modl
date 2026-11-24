import type { ImportPhaseId, ImportStatusId, IssueSeverityId } from '@/hooks/data/hosted-import';

export const STATUS_KEYS: Readonly<Record<ImportStatusId, string>> = {
  none: 'pages.setup.import.status.none',
  receiving: 'pages.setup.import.status.receiving',
  downloading: 'pages.setup.import.status.downloading',
  verifying: 'pages.setup.import.status.verifying',
  review: 'pages.setup.import.status.review',
  applying: 'pages.setup.import.status.applying',
  paused: 'pages.setup.import.status.paused',
  completed: 'pages.setup.import.status.completed',
  abandoned: 'pages.setup.import.status.abandoned',
  failed: 'pages.setup.import.status.failed',
};

export const PHASE_KEYS: Readonly<Record<ImportPhaseId, string>> = {
  verify: 'pages.setup.import.phase.verify',
  begin: 'pages.setup.import.phase.begin',
  configuration: 'pages.setup.import.phase.configuration',
  files: 'pages.setup.import.phase.files',
  players: 'pages.setup.import.phase.players',
  links: 'pages.setup.import.phase.links',
  tickets: 'pages.setup.import.phase.tickets',
  replays: 'pages.setup.import.phase.replays',
  chatLogs: 'pages.setup.import.phase.chatLogs',
  commandLogs: 'pages.setup.import.phase.commandLogs',
  auditLogs: 'pages.setup.import.phase.auditLogs',
  access: 'pages.setup.import.phase.access',
  subscriptions: 'pages.setup.import.phase.subscriptions',
  finish: 'pages.setup.import.phase.finish',
};

export const SEVERITY_KEYS: Readonly<Record<IssueSeverityId, string>> = {
  info: 'pages.setup.import.severity.info',
  changed: 'pages.setup.import.severity.changed',
  lost: 'pages.setup.import.severity.lost',
  action: 'pages.setup.import.severity.action',
};

const SUBJECT_KEYS: Readonly<Record<string, string>> = {
  players: 'pages.setup.import.subjects.players',
  punishments: 'pages.setup.import.subjects.punishments',
  notifications: 'pages.setup.import.subjects.notifications',
  links: 'pages.setup.import.subjects.links',
  tickets: 'pages.setup.import.subjects.tickets',
  replays: 'pages.setup.import.subjects.replays',
  files: 'pages.setup.import.subjects.files',
  chat_logs: 'pages.setup.import.subjects.chatLogs',
  command_logs: 'pages.setup.import.subjects.commandLogs',
  audit_logs: 'pages.setup.import.subjects.auditLogs',
  staff: 'pages.setup.import.subjects.staff',
  roles: 'pages.setup.import.subjects.roles',
  settings: 'pages.setup.import.subjects.settings',
  categories: 'pages.setup.import.subjects.categories',
  articles: 'pages.setup.import.subjects.articles',
  cards: 'pages.setup.import.subjects.cards',
};

const BLOCKER_KEYS: Readonly<Record<string, string>> = {
  SETUP_COMPLETED: 'pages.setup.import.blocked.setupCompleted',
  ALREADY_IMPORTED: 'pages.setup.import.blocked.alreadyImported',
  HAS_PUNISHMENTS: 'pages.setup.import.blocked.hasPunishments',
  HAS_STAFF: 'pages.setup.import.blocked.hasStaff',
  HAS_ARTICLES: 'pages.setup.import.blocked.hasArticles',
  PLUGIN_IMPORT_ACTIVE: 'pages.setup.import.blocked.pluginImport',
  STORAGE_MOVING: 'pages.setup.import.blocked.storageMoving',
  DATABASE_MOVING: 'pages.setup.import.blocked.databaseMoving',
};

const REASON_KEYS: Readonly<Record<string, string>> = {
  LINK_EXPIRED: 'pages.setup.import.reasons.linkExpired',
  LINK_UNREACHABLE: 'pages.setup.import.reasons.linkUnreachable',
  LINK_REDIRECTED: 'pages.setup.import.reasons.linkBad',
  LINK_BAD_RESPONSE: 'pages.setup.import.reasons.linkBad',
  LINK_REENTRY_REQUIRED: 'pages.setup.import.reasons.linkReentry',
  ARCHIVE_EXPECTED_HASH: 'pages.setup.import.reasons.expectedHash',
  ARCHIVE_VERSION: 'pages.setup.import.reasons.archiveVersion',
  ARCHIVE_TOO_LARGE: 'pages.setup.import.reasons.archiveTooLarge',
  ARCHIVE_TOO_MANY_FILES: 'pages.setup.import.reasons.archiveTooLarge',
  NOT_ELIGIBLE: 'pages.setup.import.reasons.notEligible',
  DISK_SPACE: 'pages.setup.import.reasons.diskSpace',
  DATABASE_UNAVAILABLE: 'pages.setup.import.reasons.database',
  STORAGE_UNAVAILABLE: 'pages.setup.import.reasons.storage',
  STORAGE_LIMIT: 'pages.setup.import.reasons.storageLimit',
  ARCHIVE_MISSING: 'pages.setup.import.reasons.archiveMissing',
};

const ERROR_KEYS: Readonly<Record<string, string>> = {
  IMPORT_NOT_AVAILABLE: 'pages.setup.import.errors.notAvailable',
  IMPORT_IN_PROGRESS: 'pages.setup.import.errors.inProgress',
  IMPORT_TOO_LARGE: 'pages.setup.import.reasons.archiveTooLarge',
  IMPORT_DISK_SPACE: 'pages.setup.import.reasons.diskSpace',
  IMPORT_LINK_NOT_ALLOWED: 'pages.setup.import.errors.linkNotAllowed',
  IMPORT_STAFF_SELECTION: 'pages.setup.import.errors.staffSelection',
  IMPORT_STORAGE_NOT_OWNED: 'pages.setup.import.errors.storageNotOwned',
  IMPORT_WRONG_STATE: 'pages.setup.import.errors.wrongState',
  IMPORT_UPLOAD_OFFSET: 'pages.setup.import.errors.uploadOffset',
};

const ISSUE_KEYS: Readonly<Record<string, string>> = {
  PUNISHMENT_ROLLBACK_ENDS: 'pages.setup.import.issues.rollbackEnds',
  PUNISHMENT_DELETED_TYPE_ENFORCED: 'pages.setup.import.issues.deletedTypeEnforced',
  PUNISHMENT_WITHOUT_DATA: 'pages.setup.import.issues.punishmentWithoutData',
  PUNISHMENT_RESTRICTION_UNKNOWN: 'pages.setup.import.issues.restrictionUnknown',
  PUNISHMENT_CATEGORY_UNKNOWN: 'pages.setup.import.issues.categoryUnknown',
  STAT_WIPE_PENDING: 'pages.setup.import.issues.statWipePending',
  WEBHOOK_DISABLED: 'pages.setup.import.issues.webhookDisabled',
  AI_DISABLED: 'pages.setup.import.issues.aiDisabled',
  STAFF_NOT_RESTORED: 'pages.setup.import.issues.staffNotRestored',
  STAFF_ACCESS_NOT_RESTORED: 'pages.setup.import.issues.staffAccessNotRestored',
  SUBSCRIPTIONS_NOT_RESTORED: 'pages.setup.import.issues.subscriptionsNotRestored',
  COMMAND_REDACTED: 'pages.setup.import.issues.commandRedacted',
  LOG_NOT_KEPT: 'pages.setup.import.issues.logNotKept',
  IP_NOT_KEPT: 'pages.setup.import.issues.ipNotKept',
  IP_INVALID: 'pages.setup.import.issues.ipInvalid',
  URL_LEFT_REMOTE: 'pages.setup.import.issues.urlLeftRemote',
  FILE_SKIPPED_BY_EXPORT: 'pages.setup.import.issues.fileSkipped',
  COLLECTION_EXCLUDED: 'pages.setup.import.issues.collectionExcluded',
  RECORD_UNREADABLE: 'pages.setup.import.issues.recordUnreadable',
  RECORD_TOO_LARGE: 'pages.setup.import.issues.recordUnreadable',
  RECORD_REJECTED: 'pages.setup.import.issues.recordUnreadable',
};

export const UNKNOWN_ISSUE_KEY = 'pages.setup.import.issues.unknown';
export const UNKNOWN_ERROR_KEY = 'pages.setup.import.errors.unknown';
export const UNKNOWN_REASON_KEY = 'pages.setup.import.reasons.unknown';
export const UNKNOWN_SUBJECT_KEY = 'pages.setup.import.subjects.other';

export function subjectKey(subject: string): string {
  return SUBJECT_KEYS[subject] ?? UNKNOWN_SUBJECT_KEY;
}

export function blockerKey(code: string): string {
  return BLOCKER_KEYS[code] ?? UNKNOWN_REASON_KEY;
}

export function reasonKey(code: string): string {
  return REASON_KEYS[code] ?? (code.startsWith('ARCHIVE_') ? 'pages.setup.import.reasons.archiveInvalid' : UNKNOWN_REASON_KEY);
}

export function errorKey(code: string | null): string {
  return code === null ? UNKNOWN_ERROR_KEY : ERROR_KEYS[code] ?? UNKNOWN_ERROR_KEY;
}

export function issueKey(code: string): string {
  return ISSUE_KEYS[code] ?? UNKNOWN_ISSUE_KEY;
}
