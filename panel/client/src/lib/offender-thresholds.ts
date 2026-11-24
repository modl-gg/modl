export interface OffenderPointThresholds {
  medium: number;
  habitual: number;
}

export const OFFENDER_POINTS_MIN = 1;
export const OFFENDER_POINTS_SCALE_MAX = 30;

export function offenderPointsScaleMax(saved: OffenderPointThresholds): number {
  return Math.max(OFFENDER_POINTS_SCALE_MAX, saved.habitual);
}

export function withMediumThreshold(thresholds: OffenderPointThresholds, medium: number, scaleMax: number): OffenderPointThresholds {
  const clamped = Math.min(Math.max(medium, OFFENDER_POINTS_MIN), scaleMax - 1);
  return { medium: clamped, habitual: Math.max(thresholds.habitual, clamped + 1) };
}

export function withHabitualThreshold(thresholds: OffenderPointThresholds, habitual: number, scaleMax: number): OffenderPointThresholds {
  const clamped = Math.min(Math.max(habitual, OFFENDER_POINTS_MIN + 1), scaleMax);
  return { medium: Math.min(thresholds.medium, clamped - 1), habitual: clamped };
}
