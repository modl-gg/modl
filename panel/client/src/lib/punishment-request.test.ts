import test from 'node:test';
import assert from 'node:assert/strict';

import { buildPunishmentRequest, isWebUrl, punishmentFormIssue, type PunishmentRequestContext } from './punishment-request.ts';

const HOUR = 60 * 60 * 1000;

const context: PunishmentRequestContext = {
  punishmentTypes: [
    { name: 'Kick', ordinal: 0 },
    { name: 'Manual Mute', ordinal: 1 },
    { name: 'Linked Ban', ordinal: 4 },
    { name: 'Chat Abuse', ordinal: 6 },
    { name: 'Bad Skin', ordinal: 7, singleSeverityPunishment: true },
    { name: 'Bad Name', ordinal: 8, permanentUntilUsernameChange: true },
  ],
  ticketIds: ['abc123'],
};

await test('a form without a category is rejected first', () => {
  assert.deepEqual(buildPunishmentRequest({}, context), { ok: false, issue: 'missingCategory' });
});

await test('a category that is not a configured type is unknown before anything else is checked', () => {
  assert.deepEqual(buildPunishmentRequest({ selectedPunishmentCategory: 'Gone' }, context), { ok: false, issue: 'unknownType' });
});

await test('manual punishments that are shown to the player may go without a reason', () => {
  const result = buildPunishmentRequest({ selectedPunishmentCategory: 'Kick', reason: '  ' }, context);
  assert.ok(result.ok);
  assert.equal(result.request.reason, undefined);
});

await test('multi severity punishments need a severity', () => {
  assert.deepEqual(buildPunishmentRequest({ selectedPunishmentCategory: 'Chat Abuse' }, context), { ok: false, issue: 'missingSeverity' });
});

await test('a single severity punishment without a level leaves the decision to the history on the server', () => {
  const result = buildPunishmentRequest({ selectedPunishmentCategory: 'Bad Skin' }, context);
  assert.ok(result.ok);
  assert.equal(result.request.severity, 'single');
  assert.equal(result.request.status, undefined);
});

await test('punishments that last until the player changes their name or skin need no severity and send none', () => {
  const result = buildPunishmentRequest({ selectedPunishmentCategory: 'Bad Name', selectedSeverity: 'Regular' }, context);
  assert.equal(result.ok, true);
  assert.ok(result.ok);
  assert.equal(result.request.typeOrdinal, 8);
  assert.equal(result.request.severity, undefined);
  assert.equal(result.request.status, undefined);
});

await test('the form issue the apply button checks is the one the request is rejected with', () => {
  const mute = { name: 'Manual Mute', ordinal: 1 };
  const form = { selectedPunishmentCategory: 'Manual Mute', reason: 'spam' };
  assert.equal(punishmentFormIssue(form, mute), 'invalidDuration');
  assert.deepEqual(buildPunishmentRequest(form, context), { ok: false, issue: 'invalidDuration' });
  assert.equal(punishmentFormIssue({ ...form, isPermanent: true }, mute), null);
});

await test('a manual mute sends its duration in the request and needs a known unit unless it is permanent', () => {
  const mute = { selectedPunishmentCategory: 'Manual Mute', reason: 'spam' };
  assert.deepEqual(buildPunishmentRequest({ ...mute, duration: { value: 0, unit: 'hours' } }, context), { ok: false, issue: 'invalidDuration' });
  assert.deepEqual(buildPunishmentRequest({ ...mute, duration: { value: 2, unit: 'fortnights' } }, context), { ok: false, issue: 'invalidDuration' });
  const permanent = buildPunishmentRequest({ ...mute, isPermanent: true, duration: { value: 2, unit: 'hours' } }, context);
  assert.ok(permanent.ok);
  assert.equal(permanent.request.duration, undefined);
  const timed = buildPunishmentRequest({ ...mute, duration: { value: 2, unit: 'hours' } }, context);
  assert.ok(timed.ok);
  assert.equal(timed.request.duration, 2 * HOUR);
  assert.equal(timed.request.reason, 'spam');
});

await test('a multi severity request maps the severity and leaves duration and offense status to the server', () => {
  const result = buildPunishmentRequest({ selectedPunishmentCategory: 'Chat Abuse', selectedSeverity: 'Aggravated', silentPunishment: true }, context);
  assert.ok(result.ok);
  assert.equal(result.request.typeOrdinal, 6);
  assert.equal(result.request.severity, 'severe');
  assert.equal(result.request.status, undefined);
  assert.equal(result.request.duration, undefined);
  assert.equal(result.request.reason, undefined);
  assert.deepEqual(result.request.data, {
    silent: true,
    altBlocking: undefined,
    wipeAfterExpiry: undefined,
    banLinkedAccounts: undefined,
    kickSameIP: undefined,
    linkedBanId: undefined,
  });
});

await test('a single severity request records the chosen offense level', () => {
  const result = buildPunishmentRequest({ selectedPunishmentCategory: 'Bad Skin', selectedOffenseLevel: 'medium' }, context);
  assert.ok(result.ok);
  assert.equal(result.request.severity, 'single');
  assert.equal(result.request.status, 'medium');
  assert.equal(result.request.duration, undefined);
});

await test('manual punishments ignore a leftover severity', () => {
  const result = buildPunishmentRequest({ selectedPunishmentCategory: 'Linked Ban', selectedSeverity: 'Regular' }, context);
  assert.ok(result.ok);
  assert.equal(result.request.severity, undefined);
});

await test('options, notes, the linked ban id and tickets are carried over', () => {
  const result = buildPunishmentRequest({
    selectedPunishmentCategory: 'Linked Ban',
    altBlocking: true,
    statWiping: true,
    banLinkedAccounts: true,
    kickSameIP: true,
    banToLink: ' KQZPTRMA ',
    staffNotes: '  repeat offender ',
    attachReports: ['ticket-abc123', 'ticket-new', 'def456', ''],
  }, context);
  assert.ok(result.ok);
  assert.deepEqual(result.request.notes, [{ text: 'repeat offender' }]);
  assert.deepEqual(result.request.attachedTicketIds, ['abc123', 'def456']);
  assert.equal(result.request.data.linkedBanId, 'KQZPTRMA');
  assert.equal(result.request.data.altBlocking, true);
  assert.equal(result.request.data.wipeAfterExpiry, true);
  assert.equal(result.request.data.banLinkedAccounts, true);
  assert.equal(result.request.data.kickSameIP, true);
});

await test('evidence entries become uploaded files, links or text', () => {
  const uploaded = JSON.stringify({ url: '/v1/storage/a.png', fileName: 'a.png', fileType: 'image/png', fileSize: 42 });
  const result = buildPunishmentRequest({
    selectedPunishmentCategory: 'Chat Abuse',
    selectedSeverity: 'Lenient',
    evidence: [uploaded, 'https://cdn.example.com/clip.mp4', 'httpd crashed', ' said a bad word ', '', '{not json'],
  }, context);
  assert.ok(result.ok);
  assert.deepEqual(result.request.evidence, [
    { text: 'a.png', type: 'file', fileUrl: '/v1/storage/a.png', fileName: 'a.png', fileType: 'image/png', fileSize: 42 },
    { text: 'https://cdn.example.com/clip.mp4', type: 'url' },
    { text: 'httpd crashed', type: 'text' },
    { text: 'said a bad word', type: 'text' },
    { text: '{not json', type: 'text' },
  ]);
});

await test('only http and https links count as web urls', () => {
  assert.equal(isWebUrl('https://example.com/a'), true);
  assert.equal(isWebUrl('http://example.com'), true);
  assert.equal(isWebUrl('javascript:alert(1)'), false);
  assert.equal(isWebUrl('http://exa mple'), false);
  assert.equal(isWebUrl('not a link'), false);
});
