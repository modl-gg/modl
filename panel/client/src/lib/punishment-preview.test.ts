import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import i18next from 'i18next';
import { z } from 'zod';

import type { PunishmentPreviewResponse_SeverityPreview } from '@proto/modl/v1/punishment_pb.ts';
import { describePunishment, offenseLevelToApply, type PunishmentSelection, type PunishmentTypeConfig } from './punishment-preview.ts';

const englishLocale = z.record(z.string(), z.unknown()).parse(
  JSON.parse(readFileSync(new URL('../locales/en.json', import.meta.url), 'utf8'))
);

const i18n = i18next.createInstance();
await i18n.init({
  lng: 'en',
  resources: { en: { translation: englishLocale } },
  interpolation: { escapeValue: false },
});
const t = i18n.t.bind(i18n);

const manualMute: PunishmentTypeConfig = { name: 'Manual Mute', ordinal: 1 };
const securityBan: PunishmentTypeConfig = { name: 'Security Ban', ordinal: 3 };
const chatAbuse: PunishmentTypeConfig = {
  name: 'Chat Abuse',
  ordinal: 6,
  durations: {
    regular: { first: { value: 2, unit: 'days', type: 'mute' } },
    severe: { first: { type: 'permanent ban' } },
  },
};
const cheating: PunishmentTypeConfig = {
  name: 'Cheating',
  ordinal: 7,
  singleSeverityPunishment: true,
  singleSeverityDurations: { habitual: { value: 1, unit: 'months', type: 'ban' } },
};

function previewed(punishmentType: string, durationFormatted: string, permanent = false): PunishmentPreviewResponse_SeverityPreview {
  return {
    $typeName: 'modl.v1.PunishmentPreviewResponse.SeverityPreview',
    severity: 'regular',
    durationFormatted,
    punishmentType,
    permanent,
    newSocialStatus: '',
    newGameplayStatus: '',
    points: 0,
    newSocialPoints: 0,
    newGameplayPoints: 0,
    durationMs: 0n,
  };
}

const describe = (type: PunishmentTypeConfig, selection: PunishmentSelection) =>
  describePunishment(type, selection, null, t);

await test('a manual mute shows the duration the staff member entered', () => {
  assert.equal(describe(manualMute, { duration: { value: 1, unit: 'hours' } }), 'Manual Mute - 1 hour mute');
  assert.equal(describe(manualMute, { duration: { value: 24, unit: 'hours' } }), 'Manual Mute - 24 hours mute');
  assert.equal(describe(manualMute, { isPermanent: true }), 'Manual Mute - Permanent mute');
});

await test('a security ban never shows a duration left over from another punishment type', () => {
  assert.equal(describe(securityBan, { duration: { value: 3, unit: 'days' } }), 'Security Ban - ban');
});

await test('a configured punishment shows the outcome the server previews for the selected severity', () => {
  const regular = describePunishment(chatAbuse, { selectedSeverity: 'Regular' }, previewed('mute', '8d'), t);
  const aggravated = describePunishment(chatAbuse, { selectedSeverity: 'Aggravated' }, previewed('ban', 'Permanent', true), t);
  assert.equal(regular, 'Chat Abuse (Regular) - 8d mute');
  assert.equal(aggravated, 'Chat Abuse (Aggravated) - Permanent ban');
});

await test('no first-offense duration is promised while the preview of a repeat offender is still loading', () => {
  assert.equal(describe(chatAbuse, { selectedSeverity: 'Regular' }), 'Chat Abuse (Regular)');
  assert.equal(describe(cheating, { selectedOffenseLevel: 'habitual' }), 'Cheating (Habitual offense)');
});

await test('a single-severity punishment shows the label and the duration of the level that will be applied', () => {
  const picked = offenseLevelToApply(cheating, 'medium', 'habitual');
  assert.equal(
    describePunishment(cheating, { selectedOffenseLevel: picked }, previewed('ban', '14d'), t),
    'Cheating (Medium offense) - 14d ban',
  );
});

await test('history preselects the level once the preview arrives', () => {
  assert.equal(offenseLevelToApply(cheating, undefined, undefined), undefined);
  assert.equal(offenseLevelToApply(cheating, undefined, 'low'), 'first');
  assert.equal(offenseLevelToApply(cheating, undefined, 'medium'), 'medium');
  assert.equal(offenseLevelToApply(cheating, undefined, 'habitual'), 'habitual');
});

await test('a manual pick survives a preview that arrives afterwards, in both directions', () => {
  assert.equal(offenseLevelToApply(cheating, 'first', undefined), 'first');
  assert.equal(offenseLevelToApply(cheating, 'first', 'habitual'), 'first');
  assert.equal(offenseLevelToApply(cheating, 'habitual', 'low'), 'habitual');
});

await test('changing to a type without levels applies no level from the previous type', () => {
  const badName: PunishmentTypeConfig = { name: 'Bad Name', singleSeverityPunishment: true, permanentUntilUsernameChange: true };
  assert.equal(offenseLevelToApply(chatAbuse, 'habitual', 'habitual'), undefined);
  assert.equal(offenseLevelToApply(manualMute, 'habitual', 'habitual'), undefined);
  assert.equal(offenseLevelToApply(badName, 'habitual', 'habitual'), undefined);
  assert.equal(describePunishment(badName, {}, previewed('ban', '14d'), t), 'Bad Name - ban until the username changes');
});

await test('an identity restriction says what lifts it, also before the preview loads', () => {
  const skin: PunishmentTypeConfig = { name: 'Bad Skin', permanentUntilSkinChange: true };
  const both: PunishmentTypeConfig = { name: 'Bad Look', permanentUntilSkinChange: true, permanentUntilUsernameChange: true };
  assert.equal(describePunishment(skin, {}, null, t), 'Bad Skin - ban until the skin changes');
  assert.equal(describePunishment(both, {}, null, t), 'Bad Look - ban until the username or skin changes');
});

await test('every manual punishment type previews the outcome its descriptor defines', () => {
  const leftover: PunishmentSelection = { duration: { value: 3, unit: 'days' }, isPermanent: true };
  const timed: PunishmentSelection = { duration: { value: 2, unit: 'days' } };
  const expectations: readonly (readonly [PunishmentTypeConfig, PunishmentSelection, string])[] = [
    [{ name: 'Kick', ordinal: 0 }, leftover, 'Kick - kick'],
    [{ name: 'Manual Ban', ordinal: 2 }, timed, 'Manual Ban - 2 days ban'],
    [{ name: 'Manual Ban', ordinal: 2 }, { isPermanent: true }, 'Manual Ban - Permanent ban'],
    [securityBan, leftover, 'Security Ban - ban'],
    [{ name: 'Linked Ban', ordinal: 4 }, leftover, 'Linked Ban - ban'],
    [{ name: 'Blacklist', ordinal: 5 }, timed, 'Blacklist - Permanent blacklist'],
  ];
  for (const [type, selection, expected] of expectations) {
    assert.equal(describe(type, selection), expected);
  }
});
