import test from 'node:test';
import assert from 'node:assert/strict';
import { chatOpacity, chatRows, wrapSegments } from './chat-layout.ts';

const plain = { color: '#ffffff', bold: false, italic: false, underlined: false, strikethrough: false, obfuscated: false };
const measure = (text: string) => text.length * 6;

await test('chat lines fade out during the last second of ten', () => {
  assert.equal(chatOpacity(0), 1);
  assert.equal(chatOpacity(8_999), 1);
  assert.ok(chatOpacity(9_500) < 0.3);
  assert.equal(chatOpacity(10_000), 0);
});

await test('long messages wrap at the chat width keeping styles', () => {
  const rows = wrapSegments([{ ...plain, text: 'aaaa bbbb cccc' }, { ...plain, text: ' dddd', bold: true }], 60, measure);
  assert.equal(rows.length, 2);
  assert.equal(rows[1]?.at(-1)?.bold, true);
});

await test('the newest line is the bottom row and old lines are dropped', () => {
  const lines = [
    { timeMs: 0, segments: [{ ...plain, text: 'old' }] },
    { timeMs: 15_000, segments: [{ ...plain, text: 'new' }] },
  ];
  const rows = chatRows(lines, 16_000, measure);
  assert.equal(rows.length, 1);
  assert.equal(rows[0]?.segments[0]?.text, 'new');
});
