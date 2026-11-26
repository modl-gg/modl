import type { ChatLine, ChatSegment } from '../../engine/ReplayPlayer';

export const CHAT_WIDTH = 320;
export const CHAT_LINE_HEIGHT = 9;
export const CHAT_VISIBLE_LINES = 10;
const CHAT_LIFETIME_MS = 10_000;
const BACKGROUND_OPACITY = 0.5;

export type Measure = (text: string, bold: boolean) => number;

export interface ChatRow {
  readonly key: string;
  readonly segments: readonly ChatSegment[];
  readonly textAlpha: number;
  readonly backgroundAlpha: number;
}

export function chatOpacity(ageMs: number): number {
  const remaining = Math.min(1, Math.max(0, (1 - ageMs / CHAT_LIFETIME_MS) * 10));
  return remaining * remaining;
}

function splitWords(segment: ChatSegment): ChatSegment[] {
  return (segment.text.match(/\S+\s*|\s+/g) ?? []).map((text) => ({ ...segment, text }));
}

function breakWord(word: ChatSegment, width: number, measure: Measure): ChatSegment[] {
  const pieces: ChatSegment[] = [];
  let current = '';
  for (const character of word.text) {
    if (current !== '' && measure(current + character, word.bold) > width) {
      pieces.push({ ...word, text: current });
      current = '';
    }
    current += character;
  }
  if (current !== '') pieces.push({ ...word, text: current });
  return pieces;
}

export function wrapSegments(segments: readonly ChatSegment[], width: number, measure: Measure): ChatSegment[][] {
  const rows: ChatSegment[][] = [[]];
  let used = 0;
  const words = segments.flatMap((segment) => segment.text.split('\n').flatMap((part, index) => {
    const pieces = splitWords({ ...segment, text: part });
    return index === 0 ? pieces : [null, ...pieces];
  }));
  for (const word of words) {
    if (word === null) {
      rows.push([]);
      used = 0;
      continue;
    }
    const wordWidth = measure(word.text.trimEnd(), word.bold);
    if (used > 0 && used + wordWidth > width) {
      rows.push([]);
      used = 0;
      if (word.text.trim() === '') continue;
    }
    const pieces = wordWidth > width ? breakWord(word, width, measure) : [word];
    pieces.forEach((piece, index) => {
      if (index > 0) {
        rows.push([]);
        used = 0;
      }
      rows.at(-1)?.push(piece);
      used += measure(piece.text, piece.bold);
    });
  }
  return rows.filter((row) => row.some((segment) => segment.text.trim() !== ''));
}

export function chatRows(lines: readonly ChatLine[], nowMs: number, measure: Measure): ChatRow[] {
  const rows: ChatRow[] = [];
  for (let lineIndex = lines.length - 1; lineIndex >= 0 && rows.length < CHAT_VISIBLE_LINES; lineIndex -= 1) {
    const line = lines[lineIndex];
    if (line === undefined) continue;
    const opacity = chatOpacity(nowMs - line.timeMs);
    if (opacity <= 0.01) break;
    const wrapped = wrapSegments(line.segments, CHAT_WIDTH, measure);
    for (let rowIndex = wrapped.length - 1; rowIndex >= 0 && rows.length < CHAT_VISIBLE_LINES; rowIndex -= 1) {
      rows.push({
        key: `${String(lineIndex)}:${String(rowIndex)}`,
        segments: wrapped[rowIndex] ?? [],
        textAlpha: opacity,
        backgroundAlpha: opacity * BACKGROUND_OPACITY,
      });
    }
  }
  return rows;
}
