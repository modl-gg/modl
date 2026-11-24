import test from 'node:test';
import assert from 'node:assert/strict';

import {
  allowedModifications, appealDecisionNeedsPardon, canDecideAppeal, canShorten, endsEarlierWith, showsAppealDecision,
  type ShortenablePunishment,
} from './punishment-actions.ts';
import type { PunishmentTypeConfig } from './punishment-preview.ts';

const manualBan: PunishmentTypeConfig = { name: 'Manual Ban', canBeAltBlocking: true, canBeStatWiping: true };
const NOW = Date.parse('2026-06-01T12:00:00Z');
const HOUR = 3_600_000;
const DAY = 24 * HOUR;
const iso = (time: number) => new Date(time).toISOString();
const timedBan: ShortenablePunishment = { typeOrdinal: 2, started: iso(NOW - HOUR), expires: iso(NOW + DAY), modifications: [] };
const permanentBan: ShortenablePunishment = { typeOrdinal: 2, started: iso(NOW - HOUR), expires: null, modifications: [] };

function holding(...granted: string[]) {
  return (permission: string) => granted.some((held) => permission === held || permission.startsWith(`${held}.`));
}

await test('each narrow permission shows only its own modifications', () => {
  const cases = [
    ['punishment.modify.pardon', ['MANUAL_PARDON']],
    ['punishment.modify.duration', ['MANUAL_DURATION_CHANGE']],
    ['punishment.modify.options', ['SET_ALT_BLOCKING_FALSE', 'SET_WIPING_TRUE']],
    ['punishment.modify.note', []],
    ['punishment.modify.evidence', []],
    ['punishment.modify.tickets', []],
    ['punishment.view', []],
  ] as const;
  for (const [held, expected] of cases) {
    assert.deepEqual(allowedModifications(holding(held), { ...timedBan, altBlocking: true }, manualBan, NOW), expected, held);
  }
});

await test('full modify shows every offered modification', () => {
  assert.deepEqual(allowedModifications(holding('punishment.modify'), permanentBan, manualBan, NOW), [
    'MANUAL_DURATION_CHANGE', 'MANUAL_PARDON', 'SET_ALT_BLOCKING_TRUE', 'SET_WIPING_TRUE',
  ]);
});

await test('an identity restriction offers no options', () => {
  const badName: PunishmentTypeConfig = { name: 'Bad Name', canBeAltBlocking: true, permanentUntilUsernameChange: true };
  assert.deepEqual(allowedModifications(holding('punishment.modify.options'), permanentBan, badName, NOW), []);
});

await test('appeal decisions need the matching permission and the right to handle appeals', () => {
  const pardoner = holding('punishment.modify.pardon', 'ticket.reply.all');
  assert.equal(canDecideAppeal(pardoner, 'pardon'), true);
  assert.equal(canDecideAppeal(pardoner, 'reduce'), false);
  assert.equal(canDecideAppeal(holding('punishment.modify.duration', 'ticket.reply.all'), 'reduce'), false);
  assert.equal(canDecideAppeal(holding('punishment.modify.duration', 'punishment.modify.pardon', 'ticket.reply.all'), 'reduce'), true);
  assert.equal(canDecideAppeal(pardoner, 'reject'), false);
  assert.equal(canDecideAppeal(holding('punishment.modify.pardon'), 'pardon'), false);
  assert.equal(canDecideAppeal(holding('punishment.modify'), 'reject'), true);
});

await test('an appeal handler without the pardon permission sees approval but cannot use it', () => {
  const handler = holding('appeal.modify', 'ticket.reply.all');
  assert.equal(canDecideAppeal(handler, 'pardon'), false);
  assert.equal(showsAppealDecision(handler, 'pardon'), true);
  assert.equal(showsAppealDecision(handler, 'reduce'), true);
  assert.equal(appealDecisionNeedsPardon(handler, 'reduce'), true);
  assert.equal(appealDecisionNeedsPardon(handler, 'reject'), false);
  assert.equal(showsAppealDecision(holding('punishment.view'), 'pardon'), false);
  assert.equal(showsAppealDecision(holding('punishment.modify'), 'reject'), true);
});

await test('changing the duration to end later or never needs only the duration permission', () => {
  assert.equal(endsEarlierWith(timedBan, 2 * DAY, NOW), false);
  assert.equal(endsEarlierWith(timedBan, DAY, NOW), false);
  assert.equal(endsEarlierWith(timedBan, 0, NOW), false);
  assert.equal(endsEarlierWith(permanentBan, 0, NOW), false);
});

await test('any change that makes a punishment end earlier is a shortening', () => {
  assert.equal(endsEarlierWith(timedBan, HOUR, NOW), true);
  assert.equal(endsEarlierWith(permanentBan, 365 * DAY, NOW), true);
});

await test('ended punishments and kicks cannot be shortened', () => {
  const pardoned = { ...timedBan, modifications: [{ type: 'MANUAL_PARDON', date: iso(NOW) }] };
  assert.equal(endsEarlierWith(pardoned, HOUR, NOW), false);
  assert.equal(endsEarlierWith({ ...timedBan, typeOrdinal: 0 }, HOUR, NOW), false);
});

await test('shortening needs the duration and the pardon permission', () => {
  assert.equal(canShorten(holding('punishment.modify.duration')), false);
  assert.equal(canShorten(holding('punishment.modify.pardon')), false);
  assert.equal(canShorten(holding('punishment.modify.duration', 'punishment.modify.pardon')), true);
  assert.equal(canShorten(holding('punishment.modify')), true);
});

await test('a permanent punishment offers no duration change to staff who cannot shorten', () => {
  assert.deepEqual(allowedModifications(holding('punishment.modify.duration'), permanentBan, manualBan, NOW), []);
  assert.deepEqual(allowedModifications(holding('punishment.modify.duration'), timedBan, manualBan, NOW), ['MANUAL_DURATION_CHANGE']);
});
