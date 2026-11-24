export type VitalUnitState = 'full' | 'half' | 'empty';

export interface VitalUnit {
  readonly position: number;
  readonly state: VitalUnitState;
}

const UNITS_PER_BAR = 10;

export function computeVitalUnits(value: number): VitalUnit[] {
  return Array.from({ length: UNITS_PER_BAR }, (_, position) => {
    const remaining = value - position * 2;
    const state: VitalUnitState = remaining >= 2 ? 'full' : remaining >= 1 ? 'half' : 'empty';
    return { position, state };
  });
}
