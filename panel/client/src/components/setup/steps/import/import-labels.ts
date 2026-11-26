import { HostedImportIssueCode } from '@proto/modl/v1/hosted_import_pb.ts';
import type { ImportIssueCode, ImportPhaseId, ImportStatusId } from '@/hooks/data/hosted-import';

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

export type IssueDetails = 'none' | 'list' | 'settings';

export interface IssueLabel {
  readonly messageKey: string;
  readonly details: IssueDetails;
}

export const ISSUE_LABELS: Readonly<Record<ImportIssueCode, IssueLabel>> = {
  [HostedImportIssueCode.RECORD_UNREADABLE]: { messageKey: 'pages.setup.import.issues.recordUnreadable', details: 'none' },
  [HostedImportIssueCode.RECORD_REJECTED]: { messageKey: 'pages.setup.import.issues.recordRejected', details: 'none' },
  [HostedImportIssueCode.RECORD_TOO_LARGE]: { messageKey: 'pages.setup.import.issues.recordTooLarge', details: 'none' },
  [HostedImportIssueCode.VALUE_TRUNCATED]: { messageKey: 'pages.setup.import.issues.valueTruncated', details: 'none' },
  [HostedImportIssueCode.VALUE_DEFAULTED]: { messageKey: 'pages.setup.import.issues.valueDefaulted', details: 'none' },
  [HostedImportIssueCode.ID_REMAPPED]: { messageKey: 'pages.setup.import.issues.idRemapped', details: 'none' },
  [HostedImportIssueCode.ID_ALIASED]: { messageKey: 'pages.setup.import.issues.idAliased', details: 'none' },
  [HostedImportIssueCode.REFERENCE_DANGLING]: { messageKey: 'pages.setup.import.issues.referenceDangling', details: 'none' },
  [HostedImportIssueCode.PLAYER_INVALID_UUID]: { messageKey: 'pages.setup.import.issues.playerInvalidUuid', details: 'none' },
  [HostedImportIssueCode.PLAYER_MERGED]: { messageKey: 'pages.setup.import.issues.playerMerged', details: 'none' },
  [HostedImportIssueCode.IP_NOT_KEPT]: { messageKey: 'pages.setup.import.issues.ipNotKept', details: 'none' },
  [HostedImportIssueCode.IP_INVALID]: { messageKey: 'pages.setup.import.issues.ipInvalid', details: 'none' },
  [HostedImportIssueCode.LOG_NOT_KEPT]: { messageKey: 'pages.setup.import.issues.logNotKept', details: 'none' },
  [HostedImportIssueCode.NOTIFICATION_EXPIRED]: { messageKey: 'pages.setup.import.issues.notificationExpired', details: 'none' },
  [HostedImportIssueCode.PUNISHMENT_WITHOUT_DATA]: { messageKey: 'pages.setup.import.issues.punishmentWithoutData', details: 'none' },
  [HostedImportIssueCode.PUNISHMENT_ROLLBACK_ENDS]: { messageKey: 'pages.setup.import.issues.rollbackEnds', details: 'none' },
  [HostedImportIssueCode.PUNISHMENT_DELETED_TYPE_ENFORCED]: { messageKey: 'pages.setup.import.issues.deletedTypeEnforced', details: 'list' },
  [HostedImportIssueCode.PUNISHMENT_CATEGORY_UNKNOWN]: { messageKey: 'pages.setup.import.issues.categoryUnknown', details: 'none' },
  [HostedImportIssueCode.PUNISHMENT_RESTRICTION_UNKNOWN]: { messageKey: 'pages.setup.import.issues.restrictionUnknown', details: 'none' },
  [HostedImportIssueCode.PUNISHMENT_STORED_NONE]: { messageKey: 'pages.setup.import.issues.storedNone', details: 'none' },
  [HostedImportIssueCode.PUNISHMENT_DURATION_SATURATED]: { messageKey: 'pages.setup.import.issues.durationSaturated', details: 'none' },
  [HostedImportIssueCode.STAT_WIPE_PENDING]: { messageKey: 'pages.setup.import.issues.statWipePending', details: 'list' },
  [HostedImportIssueCode.KICK_MARKED_DELIVERED]: { messageKey: 'pages.setup.import.issues.kickMarkedDelivered', details: 'none' },
  [HostedImportIssueCode.TYPE_INVALID]: { messageKey: 'pages.setup.import.issues.typeInvalid', details: 'list' },
  [HostedImportIssueCode.TYPE_TOMBSTONE]: { messageKey: 'pages.setup.import.issues.typeTombstone', details: 'none' },
  [HostedImportIssueCode.ROLE_RENAMED]: { messageKey: 'pages.setup.import.issues.roleRenamed', details: 'list' },
  [HostedImportIssueCode.PERMISSION_DROPPED]: { messageKey: 'pages.setup.import.issues.permissionDropped', details: 'list' },
  [HostedImportIssueCode.STAFF_NOT_RESTORED]: { messageKey: 'pages.setup.import.issues.staffNotRestored', details: 'list' },
  [HostedImportIssueCode.STAFF_ACCESS_NOT_RESTORED]: { messageKey: 'pages.setup.import.issues.staffAccessNotRestored', details: 'none' },
  [HostedImportIssueCode.SUBSCRIPTIONS_NOT_RESTORED]: { messageKey: 'pages.setup.import.issues.subscriptionsNotRestored', details: 'none' },
  [HostedImportIssueCode.STAFF_RENAMED]: { messageKey: 'pages.setup.import.issues.staffRenamed', details: 'list' },
  [HostedImportIssueCode.STAFF_LINK_DROPPED]: { messageKey: 'pages.setup.import.issues.staffLinkDropped', details: 'list' },
  [HostedImportIssueCode.SETTING_UNREADABLE]: { messageKey: 'pages.setup.import.issues.settingUnreadable', details: 'settings' },
  [HostedImportIssueCode.WEBHOOK_DISABLED]: { messageKey: 'pages.setup.import.issues.webhookDisabled', details: 'none' },
  [HostedImportIssueCode.AI_DISABLED]: { messageKey: 'pages.setup.import.issues.aiDisabled', details: 'none' },
  [HostedImportIssueCode.SLUG_RENAMED]: { messageKey: 'pages.setup.import.issues.slugRenamed', details: 'list' },
  [HostedImportIssueCode.ARTICLE_ORPHANED]: { messageKey: 'pages.setup.import.issues.articleOrphaned', details: 'none' },
  [HostedImportIssueCode.URL_UNSAFE]: { messageKey: 'pages.setup.import.issues.urlUnsafe', details: 'none' },
  [HostedImportIssueCode.URL_LEFT_REMOTE]: { messageKey: 'pages.setup.import.issues.urlLeftRemote', details: 'none' },
  [HostedImportIssueCode.FILE_KEY_RENAMED]: { messageKey: 'pages.setup.import.issues.fileKeyRenamed', details: 'none' },
  [HostedImportIssueCode.FILE_CASE_CONFLICT]: { messageKey: 'pages.setup.import.issues.fileCaseConflict', details: 'none' },
  [HostedImportIssueCode.FILE_TYPE_NEUTRALISED]: { messageKey: 'pages.setup.import.issues.fileTypeNeutralised', details: 'none' },
  [HostedImportIssueCode.FILE_SKIPPED_BY_EXPORT]: { messageKey: 'pages.setup.import.issues.fileSkipped', details: 'none' },
  [HostedImportIssueCode.FILE_CONFLICT]: { messageKey: 'pages.setup.import.issues.fileConflict', details: 'none' },
  [HostedImportIssueCode.REPLAY_FILE_MISSING]: { messageKey: 'pages.setup.import.issues.replayFileMissing', details: 'none' },
  [HostedImportIssueCode.COMMAND_REDACTED]: { messageKey: 'pages.setup.import.issues.commandRedacted', details: 'none' },
  [HostedImportIssueCode.COLLECTION_UNKNOWN]: { messageKey: 'pages.setup.import.issues.collectionUnknown', details: 'none' },
};

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
