import type { TFunction } from 'i18next';
import type { PunishmentPreviewResponse_SeverityPreview } from '@proto/modl/v1/punishment_pb.ts';
import {
  isManualPunishment,
  manualPunishmentOf,
  type ManualPunishmentAction,
  type ManualPunishmentDescriptor,
} from './manual-punishments.ts';

export type DurationUnit = 'seconds' | 'minutes' | 'hours' | 'days' | 'weeks' | 'months';
export type PunishmentSeverity = 'Lenient' | 'Regular' | 'Aggravated';
export type OffenseLevel = 'first' | 'medium' | 'habitual';

interface PunishmentDurationConfig {
  value?: number | undefined;
  unit?: string | undefined;
  type?: string | undefined;
}

interface PunishmentSeverityDurations {
  first?: PunishmentDurationConfig | undefined;
  medium?: PunishmentDurationConfig | undefined;
  habitual?: PunishmentDurationConfig | undefined;
}

export interface PunishmentTypeConfig {
  id?: number | string | undefined;
  name: string;
  category?: string | undefined;
  customizable?: boolean | undefined;
  ordinal?: number | undefined;
  singleSeverityPunishment?: boolean | undefined;
  singleSeverityDurations?: PunishmentSeverityDurations | undefined;
  durations?: {
    low?: PunishmentSeverityDurations | undefined;
    regular?: PunishmentSeverityDurations | undefined;
    severe?: PunishmentSeverityDurations | undefined;
  } | undefined;
  permanentUntilSkinChange?: boolean | undefined;
  permanentUntilUsernameChange?: boolean | undefined;
  canBeAltBlocking?: boolean | undefined;
  canBeStatWiping?: boolean | undefined;
}

export interface PunishmentSelection {
  selectedSeverity?: PunishmentSeverity | undefined;
  selectedOffenseLevel?: OffenseLevel | undefined;
  duration?: {
    value: number;
    unit: DurationUnit;
  } | undefined;
  isPermanent?: boolean | undefined;
}

export const SEVERITY_OPTIONS = [
  { value: 'Lenient', labelKey: 'punishment.severityLenient' },
  { value: 'Regular', labelKey: 'punishment.severityRegular' },
  { value: 'Aggravated', labelKey: 'punishment.severityAggravated' },
] as const satisfies readonly { value: PunishmentSeverity; labelKey: string }[];

export const OFFENSE_LEVELS = [
  { id: 'first', status: 'low', labelKey: 'punishment.offenseFirst', previewKey: 'punishment.preview.offenseFirst' },
  { id: 'medium', status: 'medium', labelKey: 'punishment.offenseMedium', previewKey: 'punishment.preview.offenseMedium' },
  { id: 'habitual', status: 'habitual', labelKey: 'punishment.offenseHabitual', previewKey: 'punishment.preview.offenseHabitual' },
] as const satisfies readonly { id: OffenseLevel; status: string; labelKey: string; previewKey: string }[];

const SECOND_MILLIS = 1000;
const MINUTE_MILLIS = 60 * SECOND_MILLIS;
const HOUR_MILLIS = 60 * MINUTE_MILLIS;
const DAY_MILLIS = 24 * HOUR_MILLIS;

export const DURATION_UNITS = [
  { value: 'seconds', millis: SECOND_MILLIS, labelKey: 'punishment.seconds', previewKey: 'punishment.preview.seconds' },
  { value: 'minutes', millis: MINUTE_MILLIS, labelKey: 'punishment.minutes', previewKey: 'punishment.preview.minutes' },
  { value: 'hours', millis: HOUR_MILLIS, labelKey: 'punishment.hours', previewKey: 'punishment.preview.hours' },
  { value: 'days', millis: DAY_MILLIS, labelKey: 'punishment.days', previewKey: 'punishment.preview.days' },
  { value: 'weeks', millis: 7 * DAY_MILLIS, labelKey: 'punishment.weeks', previewKey: 'punishment.preview.weeks' },
  { value: 'months', millis: 30 * DAY_MILLIS, labelKey: 'punishment.months', previewKey: 'punishment.preview.months' },
] as const satisfies readonly { value: DurationUnit; millis: number; labelKey: string; previewKey: string }[];

export const DURATION_UNIT_MILLIS: ReadonlyMap<string, number> = new Map(DURATION_UNITS.map((unit) => [unit.value, unit.millis]));

const ACTION_KEYS = {
  kick: { label: 'punishment.preview.actionKick', permanent: 'punishment.preview.permanentKick' },
  mute: { label: 'punishment.preview.actionMute', permanent: 'punishment.preview.permanentMute' },
  ban: { label: 'punishment.preview.actionBan', permanent: 'punishment.preview.permanentBan' },
  blacklist: { label: 'punishment.preview.actionBlacklist', permanent: 'punishment.preview.permanentBlacklist' },
} as const satisfies Record<ManualPunishmentAction, { label: string; permanent: string }>;

type PunishmentAction = keyof typeof ACTION_KEYS;

interface PunishmentOutcome {
  action: string;
  permanent: boolean;
  duration: string | null;
}

export function isPermanentUntilIdentityChange(punishmentType: {
  readonly permanentUntilSkinChange?: boolean | undefined;
  readonly permanentUntilUsernameChange?: boolean | undefined;
}): boolean {
  return punishmentType.permanentUntilSkinChange === true || punishmentType.permanentUntilUsernameChange === true;
}

function identityChangeKey(punishmentType: PunishmentTypeConfig) {
  if (punishmentType.permanentUntilSkinChange !== true) return 'punishment.preview.banUntilNameChange';
  return punishmentType.permanentUntilUsernameChange === true
    ? 'punishment.preview.banUntilNameOrSkinChange'
    : 'punishment.preview.banUntilSkinChange';
}

export function findDurationUnit(value: string) {
  return DURATION_UNITS.find((unit) => unit.value === value);
}

function severityLabel(severity: PunishmentSeverity, t: TFunction): string {
  const option = SEVERITY_OPTIONS.find((candidate) => candidate.value === severity);
  return option ? t(option.labelKey) : severity;
}

function isKnownAction(action: string): action is PunishmentAction {
  return Object.hasOwn(ACTION_KEYS, action);
}

function actionLabel(action: string, t: TFunction): string {
  return isKnownAction(action) ? t(ACTION_KEYS[action].label) : action;
}

function permanentDescription(base: string, action: string, t: TFunction): string {
  return isKnownAction(action)
    ? t(ACTION_KEYS[action].permanent, { base })
    : t('punishment.preview.permanent', { base, action });
}

function formatDuration(value: number | undefined, unit: string | undefined, t: TFunction): string | null {
  if (value === undefined || !(value > 0) || !unit) {
    return null;
  }
  const knownUnit = findDurationUnit(unit);
  return knownUnit ? t(knownUnit.previewKey, { count: value }) : t('punishment.preview.durationWithUnit', { value, unit });
}

function manualOutcome(manual: ManualPunishmentDescriptor, selection: PunishmentSelection, t: TFunction): PunishmentOutcome {
  const { action } = manual;
  switch (manual.durationMode) {
    case 'none':
      return { action, permanent: false, duration: null };
    case 'permanent':
      return { action, permanent: true, duration: null };
    case 'staffChosen':
      return selection.isPermanent === true
        ? { action, permanent: true, duration: null }
        : { action, permanent: false, duration: formatDuration(selection.duration?.value, selection.duration?.unit, t) };
  }
}

function previewedOutcome(severityPreview: PunishmentPreviewResponse_SeverityPreview): PunishmentOutcome {
  return {
    action: severityPreview.punishmentType || 'ban',
    permanent: severityPreview.permanent,
    duration: severityPreview.durationFormatted || null,
  };
}

function resolveOutcome(
  punishmentType: PunishmentTypeConfig,
  selection: PunishmentSelection,
  severityPreview: PunishmentPreviewResponse_SeverityPreview | null,
  t: TFunction
): PunishmentOutcome | null {
  const manual = manualPunishmentOf(punishmentType.name);
  if (manual !== undefined) return manualOutcome(manual, selection, t);
  if (severityPreview === null) return null;
  return previewedOutcome(severityPreview);
}

export function offenseLevelToApply(
  punishmentType: PunishmentTypeConfig,
  picked: OffenseLevel | undefined,
  offenderStatus: string | undefined
): OffenseLevel | undefined {
  if (punishmentType.singleSeverityPunishment !== true) return undefined;
  if (isManualPunishment(punishmentType.name) || isPermanentUntilIdentityChange(punishmentType)) return undefined;
  return picked ?? OFFENSE_LEVELS.find((level) => level.status === offenderStatus)?.id;
}

function selectionQualifier(punishmentType: PunishmentTypeConfig, selection: PunishmentSelection, t: TFunction): string | null {
  const singleSeverity = punishmentType.singleSeverityPunishment === true;
  if (selection.selectedSeverity && !singleSeverity) {
    return severityLabel(selection.selectedSeverity, t);
  }
  if (selection.selectedOffenseLevel && singleSeverity) {
    const level = OFFENSE_LEVELS.find((candidate) => candidate.id === selection.selectedOffenseLevel);
    return level ? t(level.previewKey) : null;
  }
  return null;
}

export function describePunishment(
  punishmentType: PunishmentTypeConfig,
  selection: PunishmentSelection,
  severityPreview: PunishmentPreviewResponse_SeverityPreview | null,
  t: TFunction
): string {
  const qualifier = selectionQualifier(punishmentType, selection, t);
  const base = qualifier === null ? punishmentType.name : t('punishment.preview.qualified', { name: punishmentType.name, qualifier });
  if (!isManualPunishment(punishmentType.name) && isPermanentUntilIdentityChange(punishmentType)) {
    return t(identityChangeKey(punishmentType), { base });
  }
  const outcome = resolveOutcome(punishmentType, selection, severityPreview, t);
  if (!outcome) {
    return base;
  }
  if (outcome.permanent) {
    return permanentDescription(base, outcome.action, t);
  }
  const action = actionLabel(outcome.action, t);
  if (outcome.duration) {
    return t('punishment.preview.timed', { base, duration: outcome.duration, action });
  }
  return action ? t('punishment.preview.untimed', { base, action }) : base;
}
