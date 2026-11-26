import test from 'node:test';
import assert from 'node:assert/strict';
import { panelReplayPath, replayIdFromLink, replayIdFromReference, withoutReplayParam, withReplayParam } from './replay-reference.ts';

const origin = 'https://panel.example.com';

await test('replay links resolve only on the replay path', () => {
  assert.equal(replayIdFromLink('https://panel.example.com/replay?id=r1', origin), 'r1');
  assert.equal(replayIdFromLink('https://other.example.com/replay?id=r1', origin), null);
  assert.equal(replayIdFromLink('/replay?id=r 2', origin), 'r 2');
  assert.equal(replayIdFromLink('https://panel.example.com/tickets?id=r1', origin), null);
  assert.equal(replayIdFromLink('https://panel.example.com/replay', origin), null);
  assert.equal(replayIdFromReference('r3', origin), 'r3');
  assert.equal(replayIdFromReference('https://public.example.com/replay?id=r4', origin), 'r4');
  assert.equal(replayIdFromReference('https://panel.example.com/tickets?id=r4', origin), null);
});

await test('the dialog parameter is added and removed without touching others', () => {
  assert.equal(withReplayParam('tab=notes', 'r1'), 'tab=notes&replay=r1');
  assert.equal(withoutReplayParam('tab=notes&replay=r1'), 'tab=notes');
  assert.equal(panelReplayPath('a&b'), '/panel?replay=a%26b');
});
