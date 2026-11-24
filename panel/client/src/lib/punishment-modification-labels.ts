import type { TFunction } from 'i18next';

const MODIFICATION_RECORD_LABEL_KEYS: ReadonlyMap<string, string> = new Map([
  ['MANUAL_PARDON', 'punishment.modificationRecord.manualPardon'],
  ['APPEAL_ACCEPT', 'punishment.modificationRecord.appealAccept'],
  ['APPEAL_REJECT', 'punishment.modificationRecord.appealReject'],
  ['APPEAL_DURATION_CHANGE', 'punishment.modificationRecord.appealDurationChange'],
  ['SYSTEM_PARDON', 'punishment.modificationRecord.systemPardon'],
  ['MANUAL_DURATION_CHANGE', 'punishment.modificationRecord.manualDurationChange'],
  ['ROLLBACK', 'punishment.modificationRecord.rollback'],
  ['SET_ALT_BLOCKING_TRUE', 'punishment.modificationRecord.altBlockingOn'],
  ['SET_ALT_BLOCKING_FALSE', 'punishment.modificationRecord.altBlockingOff'],
  ['SET_WIPING_TRUE', 'punishment.modificationRecord.statWipingOn'],
  ['SET_WIPING_FALSE', 'punishment.modificationRecord.statWipingOff'],
]);

export function modificationRecordLabel(type: string, t: TFunction): string {
  const key = MODIFICATION_RECORD_LABEL_KEYS.get(type);
  return key === undefined ? type : t(key);
}
