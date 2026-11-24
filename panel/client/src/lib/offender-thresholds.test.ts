import test from 'node:test';
import assert from 'node:assert/strict';

import {
  OFFENDER_POINTS_SCALE_MAX,
  offenderPointsScaleMax,
  withHabitualThreshold,
  withMediumThreshold,
} from './offender-thresholds.ts';

const SCALE = OFFENDER_POINTS_SCALE_MAX;

await test('both thresholds share one scale so their slider positions compare', () => {
  assert.equal(offenderPointsScaleMax({ medium: 5, habitual: 10 }), OFFENDER_POINTS_SCALE_MAX);
});

await test('a saved habitual threshold above the scale widens it instead of being cut', () => {
  assert.equal(offenderPointsScaleMax({ medium: 5, habitual: 45 }), 45);
  assert.deepEqual(withHabitualThreshold({ medium: 5, habitual: 45 }, 40, 45), { medium: 5, habitual: 40 });
});

await test('raising medium past habitual pushes habitual above it', () => {
  assert.deepEqual(withMediumThreshold({ medium: 5, habitual: 10 }, 12, SCALE), { medium: 12, habitual: 13 });
});

await test('lowering habitual below medium pushes medium below it', () => {
  assert.deepEqual(withHabitualThreshold({ medium: 5, habitual: 10 }, 3, SCALE), { medium: 2, habitual: 3 });
});

await test('medium stays below the top of the scale and habitual above the bottom', () => {
  assert.deepEqual(withMediumThreshold({ medium: 5, habitual: 10 }, 30, SCALE), { medium: 29, habitual: 30 });
  assert.deepEqual(withHabitualThreshold({ medium: 5, habitual: 10 }, 1, SCALE), { medium: 1, habitual: 2 });
});

await test('a change that keeps the order leaves the other threshold alone', () => {
  assert.deepEqual(withMediumThreshold({ medium: 5, habitual: 10 }, 7, SCALE), { medium: 7, habitual: 10 });
  assert.deepEqual(withHabitualThreshold({ medium: 5, habitual: 10 }, 20, SCALE), { medium: 5, habitual: 20 });
});
