export type EnforcementEndKind = 'pardoned' | 'rolledBack';

export type PunishmentStatusKind = EnforcementEndKind | 'unstarted' | 'active' | 'inactive';

export interface PunishmentModificationLike {
  type?: string | undefined;
  date?: string | Date | null | undefined;
  effectiveDuration?: number | null | undefined;
}

export interface EnforcementEnd<M extends PunishmentModificationLike = PunishmentModificationLike> {
  readonly kind: EnforcementEndKind;
  readonly modification: M;
}

export interface PunishmentDataFlags {
  readonly active?: unknown;
}

export interface EffectivePunishmentInput<M extends PunishmentModificationLike = PunishmentModificationLike> {
  modifications?: M[] | undefined;
  active?: boolean | undefined;
  expires?: string | Date | null | undefined;
  duration?: number | null | undefined;
  data?: PunishmentDataFlags | undefined;
}

export interface PunishmentStatusInput<M extends PunishmentModificationLike = PunishmentModificationLike>
  extends EffectivePunishmentInput<M> {
  started?: string | Date | null | undefined;
  status?: string | null | undefined;
}

export interface EffectivePunishmentState<M extends PunishmentModificationLike = PunishmentModificationLike> {
  originalActive: boolean;
  originalExpiry?: string | Date | null | undefined;
  originalDuration?: number | null | undefined;
  effectiveActive: boolean;
  effectiveExpiry?: string | Date | null | undefined;
  effectiveDuration?: number | null | undefined;
  hasModifications: boolean;
  modifications: M[];
}

const PARDON_MODIFICATION_TYPES = new Set(['MANUAL_PARDON', 'APPEAL_ACCEPT', 'SYSTEM_PARDON']);
const ROLLBACK_MODIFICATION_TYPE = 'ROLLBACK';
const DURATION_CHANGE_MODIFICATION_TYPES = new Set(['MANUAL_DURATION_CHANGE', 'APPEAL_DURATION_CHANGE']);

export const isPardonModification = (modification: { type?: string | undefined } | null | undefined): boolean =>
  PARDON_MODIFICATION_TYPES.has(modification?.type ?? '');

export const isDurationChangeModification = (modification: { type?: string | undefined } | null | undefined): boolean =>
  DURATION_CHANGE_MODIFICATION_TYPES.has(modification?.type ?? '');

const isRollbackModification = (modification: { type?: string | undefined } | null | undefined): boolean =>
  modification?.type === ROLLBACK_MODIFICATION_TYPE;

export const endsEnforcement = (modification: { type?: string | undefined } | null | undefined): boolean =>
  isPardonModification(modification) || isRollbackModification(modification);

export function findEnforcementEnd<M extends PunishmentModificationLike>(modifications: readonly M[]): EnforcementEnd<M> | undefined {
  const modification = modifications.find(isPardonModification) ?? modifications.find(endsEnforcement);
  if (modification === undefined) return undefined;
  return { kind: isPardonModification(modification) ? 'pardoned' : 'rolledBack', modification };
}

const isPermanentDuration = (duration: number): boolean => duration <= 0;

const toValidTime = (value: string | Date | null | undefined): number | null => {
  if (value === null || value === undefined) return null;
  const time = value instanceof Date ? value.getTime() : new Date(value).getTime();
  return Number.isNaN(time) ? null : time;
};

const hasStarted = (started: string | Date | null | undefined): boolean =>
  started instanceof Date || (typeof started === 'string' && started.length > 0);

const modificationTime = (modification: PunishmentModificationLike): number =>
  toValidTime(modification.date) ?? 0;

export function getEffectivePunishmentState<M extends PunishmentModificationLike>(
  punishment: EffectivePunishmentInput<M>,
): EffectivePunishmentState<M> {
  const modifications = punishment.modifications ?? [];
  const originalActive = punishment.active ?? (punishment.data?.active !== false);

  let effectiveActive = originalActive;
  let effectiveExpiry = punishment.expires;
  let effectiveDuration = punishment.duration;

  const sortedModifications = [...modifications].sort(
    (a, b) => modificationTime(a) - modificationTime(b),
  );

  for (const modification of sortedModifications) {
    if (
      isDurationChangeModification(modification) &&
      modification.effectiveDuration !== null &&
      modification.effectiveDuration !== undefined
    ) {
      effectiveDuration = modification.effectiveDuration;
      if (isPermanentDuration(modification.effectiveDuration)) {
        effectiveExpiry = null;
        effectiveActive = true;
      } else {
        const changeTime = toValidTime(modification.date);
        if (changeTime !== null) {
          const newExpiry = new Date(changeTime + modification.effectiveDuration);
          effectiveExpiry = newExpiry;
          effectiveActive = newExpiry.getTime() > Date.now();
        }
      }
    }
  }

  if (modifications.some(endsEnforcement)) {
    effectiveActive = false;
  } else if (effectiveActive) {
    const expiryTime = toValidTime(effectiveExpiry);
    if (expiryTime !== null && expiryTime <= Date.now()) {
      effectiveActive = false;
    }
  }

  return {
    originalActive,
    originalExpiry: punishment.expires,
    originalDuration: punishment.duration,
    effectiveActive,
    effectiveExpiry,
    effectiveDuration,
    hasModifications: modifications.length > 0,
    modifications: sortedModifications,
  };
}

export function derivePunishmentStatusFromState<M extends PunishmentModificationLike>(
  punishment: { started?: string | Date | null | undefined; status?: string | null | undefined },
  state: EffectivePunishmentState<M>,
): PunishmentStatusKind {
  if (punishment.status?.toLowerCase() === 'pardoned') return 'pardoned';
  const end = findEnforcementEnd(state.modifications);
  if (end !== undefined) return end.kind;
  if (!hasStarted(punishment.started)) {
    return 'unstarted';
  }
  return state.effectiveActive ? 'active' : 'inactive';
}

export function derivePunishmentStatus<M extends PunishmentModificationLike>(
  punishment: PunishmentStatusInput<M>,
): PunishmentStatusKind {
  return derivePunishmentStatusFromState(punishment, getEffectivePunishmentState(punishment));
}

export function deriveEffectiveDurationMs(punishment: {
  duration?: number | null | undefined;
  started?: string | Date | null | undefined;
  expires?: string | Date | null | undefined;
}): number | null {
  const rawDuration =
    punishment.duration !== null && punishment.duration !== undefined && !isPermanentDuration(punishment.duration)
      ? punishment.duration
      : null;

  const startedTime = toValidTime(punishment.started);
  const expiresTime = toValidTime(punishment.expires);

  if (expiresTime === null) {
    return startedTime === null ? rawDuration : null;
  }
  if (startedTime === null) {
    return rawDuration;
  }
  return Math.max(expiresTime - startedTime, 0);
}
