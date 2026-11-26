import { isRecord } from '../assets/json.ts';
import type { ChatSegment } from './ReplayPlayer.ts';

export type Translate = (key: string) => string | undefined;

interface Style {
  readonly color: string;
  readonly bold: boolean;
  readonly italic: boolean;
  readonly underlined: boolean;
  readonly strikethrough: boolean;
  readonly obfuscated: boolean;
}

const NAMED_COLORS: Readonly<Record<string, string>> = {
  black: '#000000',
  dark_blue: '#0000aa',
  dark_green: '#00aa00',
  dark_aqua: '#00aaaa',
  dark_red: '#aa0000',
  dark_purple: '#aa00aa',
  gold: '#ffaa00',
  gray: '#aaaaaa',
  dark_gray: '#555555',
  blue: '#5555ff',
  green: '#55ff55',
  aqua: '#55ffff',
  red: '#ff5555',
  light_purple: '#ff55ff',
  yellow: '#ffff55',
  white: '#ffffff',
};

const CODE_COLORS = 'black dark_blue dark_green dark_aqua dark_red dark_purple gold gray dark_gray blue green aqua red light_purple yellow white'.split(' ');
const DEFAULT_STYLE: Style = { color: '#ffffff', bold: false, italic: false, underlined: false, strikethrough: false, obfuscated: false };
const MAX_DEPTH = 16;
const MAX_SEGMENTS = 256;
const MAX_NODES = 4096;
const MAX_CHARACTERS = 8192;
const MAX_COMPONENT_LENGTH = 262_144;

interface Budget {
  nodes: number;
  characters: number;
}

function colorOf(value: unknown, fallback: string): string {
  if (typeof value !== 'string') return fallback;
  if (Object.hasOwn(NAMED_COLORS, value)) return NAMED_COLORS[value] ?? fallback;
  return /^#[0-9a-fA-F]{6}$/.test(value) ? value.toLowerCase() : fallback;
}

function styleOf(node: Readonly<Record<string, unknown>>, parent: Style): Style {
  const flag = (key: string, inherited: boolean): boolean => (typeof node[key] === 'boolean' ? node[key] : inherited);
  return {
    color: colorOf(node['color'], parent.color),
    bold: flag('bold', parent.bold),
    italic: flag('italic', parent.italic),
    underlined: flag('underlined', parent.underlined),
    strikethrough: flag('strikethrough', parent.strikethrough),
    obfuscated: flag('obfuscated', parent.obfuscated),
  };
}

function pushLegacy(source: string, base: Style, out: ChatSegment[], budget: Budget): void {
  const text = source.slice(0, Math.max(0, MAX_CHARACTERS - budget.characters));
  budget.characters += text.length;
  let style = base;
  let buffer = '';
  const flush = (): void => {
    if (buffer !== '' && out.length < MAX_SEGMENTS) out.push({ text: buffer, ...style });
    buffer = '';
  };
  for (let index = 0; index < text.length; index++) {
    const character = text[index] ?? '';
    if (character === '§' && index + 1 < text.length) {
      flush();
      const code = (text[index + 1] ?? '').toLowerCase();
      index += 1;
      const colorIndex = '0123456789abcdef'.indexOf(code);
      if (colorIndex >= 0) style = { ...DEFAULT_STYLE, color: NAMED_COLORS[CODE_COLORS[colorIndex] ?? 'white'] ?? '#ffffff' };
      else if (code === 'l') style = { ...style, bold: true };
      else if (code === 'o') style = { ...style, italic: true };
      else if (code === 'n') style = { ...style, underlined: true };
      else if (code === 'm') style = { ...style, strikethrough: true };
      else if (code === 'k') style = { ...style, obfuscated: true };
      else if (code === 'r') style = base;
      continue;
    }
    buffer += character;
  }
  flush();
}

function formatTranslation(template: string, args: readonly unknown[], style: Style, context: WalkContext, depth: number): void {
  const pattern = /%(?:(\d+)\$)?([sd%])/g;
  let last = 0;
  let next = 0;
  for (let match = pattern.exec(template); match !== null; match = pattern.exec(template)) {
    if (exhausted(context.budget)) return;
    pushLegacy(template.slice(last, match.index), style, context.out, context.budget);
    last = match.index + match[0].length;
    if (match[2] === '%') {
      pushLegacy('%', style, context.out, context.budget);
      continue;
    }
    const index = match[1] === undefined ? next++ : Number.parseInt(match[1], 10) - 1;
    walk(args[index] ?? '', style, context, depth + 1);
  }
  pushLegacy(template.slice(last), style, context.out, context.budget);
}

interface WalkContext {
  readonly translate: Translate;
  readonly out: ChatSegment[];
  readonly budget: Budget;
}

function exhausted(budget: Budget): boolean {
  return budget.nodes > MAX_NODES || budget.characters >= MAX_CHARACTERS;
}

function walk(node: unknown, parent: Style, context: WalkContext, depth: number): void {
  context.budget.nodes += 1;
  if (depth > MAX_DEPTH || context.out.length >= MAX_SEGMENTS || exhausted(context.budget)) return;
  const { translate, out, budget } = context;
  if (typeof node === 'string') {
    pushLegacy(node, parent, out, budget);
    return;
  }
  if (typeof node === 'number' || typeof node === 'boolean') {
    pushLegacy(String(node), parent, out, budget);
    return;
  }
  if (Array.isArray(node)) {
    const items: readonly unknown[] = node;
    const first = items[0];
    const rest = items.slice(1);
    if (first === undefined) return;
    walk(first, parent, context, depth + 1);
    const style = isRecord(first) ? styleOf(first, parent) : parent;
    for (const child of rest) {
      if (exhausted(budget)) return;
      walk(child, style, context, depth + 1);
    }
    return;
  }
  if (!isRecord(node)) return;
  const style = styleOf(node, parent);
  const text = node['text'];
  const key = node['translate'];
  if (typeof text === 'string') pushLegacy(text, style, out, budget);
  else if (typeof key === 'string') {
    const fallback = typeof node['fallback'] === 'string' ? node['fallback'] : key;
    const args = Array.isArray(node['with']) ? node['with'] : [];
    formatTranslation(translate(key) ?? fallback, args, style, context, depth);
  } else if (typeof node['selector'] === 'string') pushLegacy(node['selector'], style, out, budget);
  else if (isRecord(node['score']) && typeof node['score']['name'] === 'string') pushLegacy(node['score']['name'], style, out, budget);
  else if (typeof node['keybind'] === 'string') pushLegacy(node['keybind'], style, out, budget);
  const extra = node['extra'];
  if (!Array.isArray(extra)) return;
  for (const child of extra) {
    if (exhausted(budget)) return;
    walk(child, style, context, depth + 1);
  }
}

export function formatChat(json: string, translate: Translate): ChatSegment[] {
  const out: ChatSegment[] = [];
  if (json.length > MAX_COMPONENT_LENGTH) return out;
  let parsed: unknown = json;
  const trimmed = json.trim();
  if (trimmed.startsWith('{') || trimmed.startsWith('[') || trimmed.startsWith('"')) {
    try {
      parsed = JSON.parse(trimmed);
    } catch {
      parsed = json;
    }
  }
  walk(parsed, DEFAULT_STYLE, { translate, out, budget: { nodes: 0, characters: 0 } }, 0);
  return out;
}

export function plainText(json: string, translate: Translate): string {
  return formatChat(json, translate).map((segment) => segment.text).join('');
}
