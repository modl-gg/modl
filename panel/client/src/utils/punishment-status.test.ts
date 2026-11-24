import test from 'node:test';
import assert from 'node:assert/strict';

import {
  derivePunishmentStatus,
  endsEnforcement,
  findEnforcementEnd,
  getEffectivePunishmentState,
} from './punishment-status.ts';

const HOUR = 60 * 60 * 1000;
const STARTED = new Date(Date.now() - 3 * HOUR).toISOString();
const ENDED = new Date(Date.now() - 2 * HOUR).toISOString();
const CHANGED = new Date(Date.now() - HOUR).toISOString();

await test('pardons and rollbacks end enforcement and nothing else does', () => {
  for (const type of ['MANUAL_PARDON', 'APPEAL_ACCEPT', 'SYSTEM_PARDON', 'ROLLBACK']) {
    assert.equal(endsEnforcement({ type }), true, type);
  }
  for (const type of ['APPEAL_REJECT', 'MANUAL_DURATION_CHANGE', 'APPEAL_DURATION_CHANGE']) {
    assert.equal(endsEnforcement({ type }), false, type);
  }
});

await test('a rollback ends a running punishment and is shown as a rollback', () => {
  const punishment = { active: true, started: STARTED, modifications: [{ type: 'ROLLBACK', date: ENDED }] };
  assert.equal(getEffectivePunishmentState(punishment).effectiveActive, false);
  assert.equal(derivePunishmentStatus(punishment), 'rolledBack');
});

await test('a rollback of a punishment that has not started is shown as a rollback', () => {
  assert.equal(derivePunishmentStatus({ started: null, modifications: [{ type: 'ROLLBACK', date: ENDED }] }), 'rolledBack');
});

await test('no duration change reactivates a punishment that a pardon or a rollback ended', () => {
  for (const end of ['MANUAL_PARDON', 'ROLLBACK']) {
    for (const effectiveDuration of [0, 24 * HOUR]) {
      for (const date of [STARTED, CHANGED]) {
        const modifications = [{ type: end, date: ENDED }, { type: 'MANUAL_DURATION_CHANGE', date, effectiveDuration }];
        assert.equal(getEffectivePunishmentState({ active: false, modifications }).effectiveActive, false);
      }
    }
  }
});

await test('a pardon takes precedence over a rollback', () => {
  const modifications = [{ type: 'ROLLBACK', date: ENDED }, { type: 'MANUAL_PARDON', date: CHANGED }];
  assert.equal(findEnforcementEnd(modifications)?.kind, 'pardoned');
  assert.equal(derivePunishmentStatus({ started: STARTED, modifications }), 'pardoned');
});

await test('a modification that ends nothing leaves the status to the active flag', () => {
  for (const type of ['APPEAL_REJECT', 'MANUAL_DURATION_CHANGE']) {
    assert.equal(derivePunishmentStatus({ active: true, started: STARTED, modifications: [{ type }] }), 'active');
    assert.equal(derivePunishmentStatus({ active: false, started: STARTED, modifications: [{ type }] }), 'inactive');
  }
});
