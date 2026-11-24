import type { TFunction } from 'i18next';
import { findEnforcementEnd, type EnforcementEndKind } from '@/utils/punishment-status';
import { isRecordedPunishment, type PunishmentHistoryEntry } from './player-profile';

export type PunishmentTimeline =
  | { readonly kind: EnforcementEndKind; readonly at: Date | null; readonly elapsedMs: number }
  | { readonly kind: 'waiting' }
  | { readonly kind: 'expired'; readonly at: Date; readonly elapsedMs: number }
  | { readonly kind: 'expires'; readonly at: Date; readonly remainingMs: number }
  | { readonly kind: 'invalidExpiry' }
  | { readonly kind: 'permanent' }
  | { readonly kind: 'duration'; readonly durationMs: number };

const MILLIS_PER_SECOND = 1000;
const MILLIS_PER_MINUTE = 60 * MILLIS_PER_SECOND;
const MILLIS_PER_HOUR = 60 * MILLIS_PER_MINUTE;
const MILLIS_PER_DAY = 24 * MILLIS_PER_HOUR;

function validDate(value: string | Date | null | undefined): Date | null {
  if (value === null || value === undefined || value === '') return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}

function expiryTimeline(expiry: string | Date, now: number): PunishmentTimeline {
  const at = validDate(expiry);
  if (at === null) return { kind: 'invalidExpiry' };
  const remainingMs = at.getTime() - now;
  return remainingMs > 0 ? { kind: 'expires', at, remainingMs } : { kind: 'expired', at, elapsedMs: -remainingMs };
}

function pastExpiryTimeline(expiry: string, now: number): PunishmentTimeline | null {
  const at = validDate(expiry);
  if (at === null || at.getTime() >= now) return null;
  return { kind: 'expired', at, elapsedMs: now - at.getTime() };
}

function hasStarted(started: string | null): boolean {
  return started !== null && started !== '';
}

export function punishmentTimeline(entry: PunishmentHistoryEntry, now: number): PunishmentTimeline | null {
  const { punishment, effectiveState } = entry;
  const end = findEnforcementEnd(effectiveState.modifications);
  if (end !== undefined) {
    const at = validDate(end.modification.date);
    return { kind: end.kind, at, elapsedMs: at === null ? 0 : now - at.getTime() };
  }
  if (isRecordedPunishment(entry) && !hasStarted(punishment.started)) return { kind: 'waiting' };
  if (!effectiveState.effectiveActive && punishment.expires !== null) return pastExpiryTimeline(punishment.expires, now);
  const effectiveExpiry = effectiveState.effectiveExpiry;
  if (effectiveState.hasModifications && effectiveExpiry !== null && effectiveExpiry !== undefined) return expiryTimeline(effectiveExpiry, now);
  const effectiveDuration = effectiveState.effectiveDuration;
  if (effectiveState.hasModifications && effectiveDuration !== null && effectiveDuration !== undefined) {
    return effectiveDuration <= 0 ? { kind: 'permanent' } : { kind: 'duration', durationMs: effectiveDuration };
  }
  return punishment.expires === null ? null : expiryTimeline(punishment.expires, now);
}

export function formatCompactDuration(durationMs: number, t: TFunction): string {
  const absolute = Math.abs(durationMs);
  const days = Math.floor(absolute / MILLIS_PER_DAY);
  const hours = Math.floor((absolute % MILLIS_PER_DAY) / MILLIS_PER_HOUR);
  const minutes = Math.floor((absolute % MILLIS_PER_HOUR) / MILLIS_PER_MINUTE);
  const seconds = Math.floor((absolute % MILLIS_PER_MINUTE) / MILLIS_PER_SECOND);
  if (days > 0) return hours > 0 ? t('player.compactDuration.daysHours', { days, hours }) : t('player.compactDuration.days', { days });
  if (hours > 0) return minutes > 0 ? t('player.compactDuration.hoursMinutes', { hours, minutes }) : t('player.compactDuration.hours', { hours });
  if (minutes > 0) return seconds > 0 ? t('player.compactDuration.minutesSeconds', { minutes, seconds }) : t('player.compactDuration.minutes', { minutes });
  return t('player.compactDuration.seconds', { seconds });
}

export function formatPunishmentDuration(durationMs: number, t: TFunction): string {
  return durationMs <= 0 ? t('punishment.permanent') : formatCompactDuration(durationMs, t);
}
