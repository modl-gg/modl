import test from 'node:test';
import assert from 'node:assert/strict';

import { createCoalescingQueue, type CoalescingTimers } from './coalescing-queue.ts';

interface FakeClock extends CoalescingTimers {
  advance: (ms: number) => void;
}

function fakeClock(): FakeClock {
  let now = 0;
  let nextHandle = 0;
  const tasks = new Map<number, { at: number; run: () => void }>();
  return {
    now: () => now,
    schedule: (run, delayMs) => {
      nextHandle += 1;
      tasks.set(nextHandle, { at: now + delayMs, run });
      return nextHandle;
    },
    cancel: (handle) => {
      if (typeof handle === 'number') tasks.delete(handle);
    },
    advance: (ms) => {
      const target = now + ms;
      for (;;) {
        const due = [...tasks].filter(([, task]) => task.at <= target).sort(([, a], [, b]) => a.at - b.at)[0];
        if (due === undefined) break;
        const [handle, task] = due;
        tasks.delete(handle);
        now = task.at;
        task.run();
      }
      now = target;
    },
  };
}

const WINDOW = { quietMs: 500, maxWaitMs: 2_000 };

function queueWithLog(clock: FakeClock) {
  const deliveries: string[][] = [];
  const queue = createCoalescingQueue<string>((item) => item, (items) => { deliveries.push(items); }, WINDOW, clock);
  return { queue, deliveries };
}

await test('a burst of the same key is delivered once after the quiet period', () => {
  const clock = fakeClock();
  const { queue, deliveries } = queueWithLog(clock);
  for (let index = 0; index < 49; index += 1) {
    queue.add(['recent-punishments']);
    clock.advance(10);
  }
  assert.deepEqual(deliveries, []);
  clock.advance(500);
  assert.deepEqual(deliveries, [['recent-punishments']]);
});

await test('different keys in one burst are delivered together in arrival order', () => {
  const clock = fakeClock();
  const { queue, deliveries } = queueWithLog(clock);
  queue.add(['a', 'b']);
  queue.add(['b', 'c']);
  clock.advance(500);
  assert.deepEqual(deliveries, [['a', 'b', 'c']]);
});

await test('a continuous stream is still delivered at the latest after the maximum wait', () => {
  const clock = fakeClock();
  const { queue, deliveries } = queueWithLog(clock);
  for (let elapsed = 0; elapsed < 2_400; elapsed += 100) {
    queue.add(['metrics']);
    clock.advance(100);
  }
  assert.equal(deliveries.length, 1);
  clock.advance(500);
  assert.equal(deliveries.length, 2);
});

await test('discard drops what is pending and keeps the queue usable', () => {
  const clock = fakeClock();
  const { queue, deliveries } = queueWithLog(clock);
  queue.add(['a']);
  queue.discard();
  clock.advance(5_000);
  queue.add(['b']);
  clock.advance(500);
  assert.deepEqual(deliveries, [['b']]);
});

await test('dispose drops what is pending and ignores later additions', () => {
  const clock = fakeClock();
  const { queue, deliveries } = queueWithLog(clock);
  queue.add(['a']);
  clock.advance(500);
  assert.deepEqual(deliveries, [['a']]);
  queue.add(['b']);
  queue.dispose();
  clock.advance(5_000);
  queue.add(['c']);
  clock.advance(5_000);
  assert.deepEqual(deliveries, [['a']]);
});

await test('flush delivers what is pending at once', () => {
  const clock = fakeClock();
  const { queue, deliveries } = queueWithLog(clock);
  queue.add(['a', 'b']);
  queue.flush();
  assert.deepEqual(deliveries, [['a', 'b']]);
  clock.advance(5_000);
  assert.deepEqual(deliveries, [['a', 'b']]);
});
