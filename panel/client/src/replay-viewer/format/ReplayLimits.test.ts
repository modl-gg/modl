import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { REPLAY_LIMITS } from './ReplayLimits.ts';

const REPOSITORY = path.resolve(import.meta.dirname, '../../../../..');
const FIXTURE = path.join(REPOSITORY, 'libs/replay-format/src/test/resources/gg/modl/minecraft/replay/format/replay-limits.txt');

function fixtureLimits(): Map<string, number> {
  const limits = new Map<string, number>();
  for (const line of readFileSync(FIXTURE, 'utf8').split('\n')) {
    if (line.trim() === '') continue;
    const [name, value, ...rest] = line.split(' ');
    assert.ok(name !== undefined && value !== undefined && rest.length === 0, line);
    limits.set(name, Number(value));
  }
  return limits;
}

void test('the viewer enforces exactly the limits shared with the Java reader', () => {
  assert.deepEqual(new Map([...REPLAY_LIMITS].sort(([left], [right]) => left.localeCompare(right))), new Map([...fixtureLimits()].sort(([left], [right]) => left.localeCompare(right))));
});
