import type { AppealDecision } from '../components/tickets/quick-actions.ts';
import { findEnforcementEnd, type PunishmentModificationLike } from '../utils/punishment-status.ts';
import { PERMISSIONS } from './permissions.ts';
import { isPermanentUntilIdentityChange, type PunishmentTypeConfig } from './punishment-preview.ts';

export type ModificationType =
  | 'MANUAL_DURATION_CHANGE'
  | 'MANUAL_PARDON'
  | 'SET_ALT_BLOCKING_TRUE'
  | 'SET_ALT_BLOCKING_FALSE'
  | 'SET_WIPING_TRUE'
  | 'SET_WIPING_FALSE';

export const APPROVAL_NEEDS_PARDON_CODE = 'APPEAL_APPROVAL_NEEDS_PARDON';
export const SHORTENING_NEEDS_PARDON_CODE = 'PUNISHMENT_SHORTENING_NEEDS_PARDON';

const KICK_ORDINAL = 0;

export type PermissionCheck = (permission: string) => boolean;

interface PunishmentOptions {
  readonly altBlocking?: boolean | undefined;
  readonly statWiping?: boolean | undefined;
}

export interface ShortenablePunishment {
  readonly typeOrdinal: number;
  readonly started?: string | null | undefined;
  readonly expires?: string | null | undefined;
  readonly modifications: readonly PunishmentModificationLike[];
}

type ModifiablePunishment = PunishmentOptions & ShortenablePunishment;

const MODIFICATION_PERMISSIONS = {
  MANUAL_DURATION_CHANGE: PERMISSIONS.PUNISHMENT_MODIFY_DURATION,
  MANUAL_PARDON: PERMISSIONS.PUNISHMENT_MODIFY_PARDON,
  SET_ALT_BLOCKING_TRUE: PERMISSIONS.PUNISHMENT_MODIFY_OPTIONS,
  SET_ALT_BLOCKING_FALSE: PERMISSIONS.PUNISHMENT_MODIFY_OPTIONS,
  SET_WIPING_TRUE: PERMISSIONS.PUNISHMENT_MODIFY_OPTIONS,
  SET_WIPING_FALSE: PERMISSIONS.PUNISHMENT_MODIFY_OPTIONS,
} as const satisfies Record<ModificationType, string>;

const SHORTENING_PERMISSIONS = [PERMISSIONS.PUNISHMENT_MODIFY_DURATION, PERMISSIONS.PUNISHMENT_MODIFY_PARDON] as const;

const APPEAL_DECISION_PERMISSIONS = {
  pardon: [PERMISSIONS.PUNISHMENT_MODIFY_PARDON],
  reduce: SHORTENING_PERMISSIONS,
  reject: [PERMISSIONS.PUNISHMENT_MODIFY],
} as const satisfies Record<AppealDecision, readonly string[]>;

function timeOf(value: string | null | undefined): number | null {
  if (value === null || value === undefined || value === '') return null;
  const time = new Date(value).getTime();
  return Number.isNaN(time) ? null : time;
}

export function canShorten(has: PermissionCheck): boolean {
  return SHORTENING_PERMISSIONS.every((permission) => has(permission));
}

export function endsEarlierWith(punishment: ShortenablePunishment, newDurationMs: number, now: number): boolean {
  if (punishment.typeOrdinal === KICK_ORDINAL || findEnforcementEnd(punishment.modifications) !== undefined || newDurationMs <= 0) {
    return false;
  }
  const expires = timeOf(punishment.expires);
  if (expires === null) return true;
  const started = timeOf(punishment.started);
  const newStart = started !== null && started > now ? started : now;
  return newStart + newDurationMs < expires;
}

function onlyShortensFurther(punishment: ShortenablePunishment, now: number): boolean {
  return endsEarlierWith(punishment, Number.MAX_SAFE_INTEGER, now);
}

export function allowedModifications(
  has: PermissionCheck,
  punishment: ModifiablePunishment,
  punishmentType: PunishmentTypeConfig | null | undefined,
  now: number,
): ModificationType[] {
  const offered: ModificationType[] = canShorten(has) || !onlyShortensFurther(punishment, now)
    ? ['MANUAL_DURATION_CHANGE', 'MANUAL_PARDON']
    : ['MANUAL_PARDON'];
  if (punishmentType && !isPermanentUntilIdentityChange(punishmentType)) {
    if (punishmentType.canBeAltBlocking === true) {
      offered.push(punishment.altBlocking === true ? 'SET_ALT_BLOCKING_FALSE' : 'SET_ALT_BLOCKING_TRUE');
    }
    if (punishmentType.canBeStatWiping === true) {
      offered.push(punishment.statWiping === true ? 'SET_WIPING_FALSE' : 'SET_WIPING_TRUE');
    }
  }
  return offered.filter((type) => has(MODIFICATION_PERMISSIONS[type]));
}

function mayDecideAppeals(has: PermissionCheck): boolean {
  return has(PERMISSIONS.PUNISHMENT_MODIFY) || has(PERMISSIONS.APPEAL_MODIFY) || has(PERMISSIONS.TICKET_REPLY_ALL);
}

export function canDecideAppeal(has: PermissionCheck, decision: AppealDecision): boolean {
  return mayDecideAppeals(has) && APPEAL_DECISION_PERMISSIONS[decision].every((permission) => has(permission));
}

export function appealDecisionNeedsPardon(has: PermissionCheck, decision: AppealDecision): boolean {
  return decision !== 'reject' && mayDecideAppeals(has) && !canDecideAppeal(has, decision);
}

export function showsAppealDecision(has: PermissionCheck, decision: AppealDecision): boolean {
  return canDecideAppeal(has, decision) || appealDecisionNeedsPardon(has, decision);
}
