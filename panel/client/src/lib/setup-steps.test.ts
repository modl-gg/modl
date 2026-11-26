import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

import {
  LAST_SETUP_STEP,
  SETUP_SCREEN_COUNT,
  SETUP_STEP_IDS,
  adjacentStep,
  firstPendingStep,
  isStepReachable,
  landingStep,
  lockedStepChoices,
  orderedStepProgress,
  parseSetupStep,
  reachableStep,
  setupPath,
  stepChoiceOf,
  stepContinueReady,
  stepPosition,
  type SetupStepProgress,
} from './setup-steps.ts';

const SETUP_PROTO = new URL('../../../../proto/proto/modl/v1/setup.proto', import.meta.url);
const ENUM_PREFIX = 'SETUP_STEP_';

function protoStepPathValues(): string[] {
  const source = readFileSync(SETUP_PROTO, 'utf8');
  const body = /enum SetupStep \{([^}]*)\}/.exec(source)?.[1] ?? '';
  return Array.from(body.matchAll(/(\w+)\s*=\s*(\d+)\s*;/g))
    .map((match) => ({ name: match[1] ?? '', number: Number(match[2]) }))
    .filter((value) => value.number !== 0)
    .sort((left, right) => left.number - right.number)
    .map((value) => value.name.slice(ENUM_PREFIX.length).toLowerCase().replaceAll('_', '-'));
}

const progress = (...entries: [SetupStepProgress['step'], SetupStepProgress['status']][]): SetupStepProgress[] =>
  entries.map(([step, status]) => ({ step, status }));

await test('the path values match the SetupStep enum of the wire contract', () => {
  assert.deepEqual(protoStepPathValues().sort(), [...SETUP_STEP_IDS].sort());
});

await test('the import step comes right after the account step', () => {
  assert.equal(SETUP_STEP_IDS[0], 'account');
  assert.equal(SETUP_STEP_IDS[1], 'import');
  assert.equal(adjacentStep('account', 1), 'import');
});

await test('the optional player address step comes after the staff step and before the AI step', () => {
  assert.equal(adjacentStep('staff', 1), 'player-addresses');
  assert.equal(adjacentStep('player-addresses', 1), 'ai');
  assert.equal(parseSetupStep('player-addresses'), 'player-addresses');
});

await test('the replays step comes after the AI step and before the finish step', () => {
  assert.equal(adjacentStep('ai', 1), 'replays');
  assert.equal(adjacentStep('replays', 1), 'content');
  assert.equal(SETUP_SCREEN_COUNT, 13);
});

await test('reported progress is put in wizard order and missing steps are pending', () => {
  const ordered = orderedStepProgress(progress(['servers', 'done'], ['account', 'done']));
  assert.deepEqual(ordered.map((entry) => entry.step), [...SETUP_STEP_IDS]);
  assert.equal(ordered.find((entry) => entry.step === 'servers')?.status, 'done');
  assert.equal(ordered.find((entry) => entry.step === 'identity')?.status, 'pending');
});

await test('the first open step is the first one not done', () => {
  assert.equal(firstPendingStep(progress(['account', 'done'], ['import', 'done'], ['identity', 'pending'])), 'identity');
  assert.equal(firstPendingStep(SETUP_STEP_IDS.map((step) => ({ step, status: 'done' }))), null);
});

await test('the wizard opens at the first pending step, or the last step when none is left', () => {
  assert.equal(landingStep(progress(['account', 'done'])), 'import');
  assert.equal(landingStep(progress(['account', 'done'], ['import', 'done'])), 'identity');
  assert.equal(landingStep(SETUP_STEP_IDS.map((step) => ({ step, status: 'done' }))), LAST_SETUP_STEP);
});

await test('only known steps are valid locations', () => {
  assert.equal(parseSetupStep('email'), 'email');
  assert.equal(parseSetupStep('finish'), null);
  assert.equal(parseSetupStep('unknown'), null);
  assert.equal(parseSetupStep(undefined), null);
});

await test('the wizard stops at both ends', () => {
  assert.equal(adjacentStep('account', -1), null);
  assert.equal(adjacentStep('import', 1), 'identity');
  assert.equal(adjacentStep('content', -1), 'replays');
  assert.equal(adjacentStep('content', 1), null);
});

await test('step positions are one based and paths are rooted at /setup', () => {
  assert.equal(stepPosition('account'), 2);
  assert.equal(stepPosition('content'), SETUP_STEP_IDS.length + 1);
  assert.equal(SETUP_SCREEN_COUNT, SETUP_STEP_IDS.length + 1);
  assert.equal(setupPath(), '/setup');
  assert.equal(setupPath('minecraft-account'), '/setup/minecraft-account');
});

await test('the setup code is screen one, so the last wizard step is the last screen', () => {
  assert.equal(stepPosition(SETUP_STEP_IDS[0]), 2);
  assert.equal(stepPosition(LAST_SETUP_STEP), SETUP_SCREEN_COUNT);
});

await test('the wizard reaches only done steps and the first open one', () => {
  const started = progress(['account', 'done'], ['import', 'done']);
  assert.equal(reachableStep('account', started), 'account');
  assert.equal(reachableStep('identity', started), 'identity');
  assert.equal(reachableStep('email', started), 'identity');
  assert.equal(isStepReachable('address', started), false);
  assert.equal(isStepReachable('content', SETUP_STEP_IDS.map((step) => ({ step, status: 'done' }))), true);
});

const OPTIONS = ['on', 'none'] as const;

await test('a configured feature decides the choice over a pick from this session', () => {
  assert.equal(stepChoiceOf({ remembered: 'none', options: OPTIONS, saved: 'on', done: true, none: 'none' }), 'on');
});

await test('a pick from this session applies while nothing is configured', () => {
  assert.equal(stepChoiceOf({ remembered: 'on', options: OPTIONS, saved: null, done: true, none: 'none' }), 'on');
  assert.equal(stepChoiceOf({ remembered: 'none', options: OPTIONS, saved: null, done: false, none: 'none' }), 'none');
});

await test('the none choice is locked while the feature is configured', () => {
  assert.deepEqual(lockedStepChoices({ saved: 'on', none: 'none' }), ['none']);
  assert.deepEqual(lockedStepChoices({ saved: null, none: 'none' }), []);
});

await test('a remembered value that is not an option of the step is ignored', () => {
  assert.equal(stepChoiceOf({ remembered: 'other', options: OPTIONS, saved: 'on', done: false, none: 'none' }), 'on');
});

await test('without a pick the saved choice applies', () => {
  assert.equal(stepChoiceOf({ remembered: undefined, options: OPTIONS, saved: 'on', done: false, none: 'none' }), 'on');
});

await test('a done step with nothing saved was left without the feature', () => {
  assert.equal(stepChoiceOf({ remembered: undefined, options: OPTIONS, saved: null, done: true, none: 'none' }), 'none');
});

await test('an open step with nothing saved or picked has no choice yet', () => {
  assert.equal(stepChoiceOf({ remembered: undefined, options: OPTIONS, saved: null, done: false, none: 'none' }), null);
});

await test('a step continues once it is done, left without the feature, or the feature is configured', () => {
  assert.equal(stepContinueReady({ value: 'on', configured: false, done: false, none: 'none' }), false);
  assert.equal(stepContinueReady({ value: null, configured: false, done: false, none: 'none' }), false);
  assert.equal(stepContinueReady({ value: 'none', configured: false, done: false, none: 'none' }), true);
  assert.equal(stepContinueReady({ value: 'on', configured: true, done: false, none: 'none' }), true);
  assert.equal(stepContinueReady({ value: 'on', configured: false, done: true, none: 'none' }), true);
  assert.equal(stepContinueReady({ value: null, configured: true, done: false, none: 'none' }), false);
});

await test('a persisted choice applies without a pick and never locks', () => {
  assert.equal(stepChoiceOf({ remembered: undefined, options: OPTIONS, saved: null, done: true, none: 'none', persisted: 'on' }), 'on');
  assert.equal(stepChoiceOf({ remembered: 'none', options: OPTIONS, saved: null, done: true, none: 'none', persisted: 'on' }), 'none');
  assert.equal(stepChoiceOf({ remembered: undefined, options: OPTIONS, saved: null, done: false, none: 'none', persisted: null }), null);
});
