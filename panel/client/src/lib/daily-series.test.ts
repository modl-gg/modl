import test from 'node:test';
import assert from 'node:assert/strict';

import { dailyRange, fillDailySeries } from './daily-series.ts';

const TODAY = new Date('2026-11-24T15:30:00Z');

await test('a period of days covers every day from its start to today', () => {
  const range = dailyRange('7d', [], TODAY);
  assert.equal(range.length, 8);
  assert.equal(range[0], '2026-11-17');
  assert.equal(range.at(-1), '2026-11-24');
});

await test('the whole history starts at the first day with data and spans at least two days', () => {
  assert.deepEqual(dailyRange('all', ['2026-11-24'], TODAY), ['2026-11-23', '2026-11-24']);
  assert.deepEqual(dailyRange('all', ['2026-11-21'], TODAY), ['2026-11-21', '2026-11-22', '2026-11-23', '2026-11-24']);
});

await test('days reported outside the period are kept rather than dropped', () => {
  const range = dailyRange('7d', ['2026-11-10', '2026-11-25'], TODAY);
  assert.equal(range[0], '2026-11-10');
  assert.equal(range.at(-1), '2026-11-25');
});

await test('a single day of data becomes a full series with zero days around it', () => {
  const filled = fillDailySeries([{ date: '2026-11-24', count: 7 }], '30d', TODAY, (date) => ({ date, count: 0 }));
  assert.equal(filled.length, 31);
  assert.deepEqual(filled.at(-1), { date: '2026-11-24', count: 7 });
  assert.deepEqual(filled[0], { date: '2026-10-25', count: 0 });
});

await test('malformed dates are ignored when the range is computed', () => {
  assert.deepEqual(dailyRange('1d', ['not-a-date'], TODAY), ['2026-11-23', '2026-11-24']);
});

await test('a series without any day of data stays empty', () => {
  assert.deepEqual(fillDailySeries([], '30d', TODAY, (date) => ({ date, count: 0 })), []);
});
