import { type JsonRecord, arrayField, booleanField, isRecord, numberField, stringField } from '../../assets/json.ts';
import type { BlockProperties } from '../../format/BlockState.ts';

export interface ModelVariant {
  readonly model: string;
  readonly x: number;
  readonly y: number;
  readonly uvlock: boolean;
  readonly weight: number;
}

type Condition = (properties: BlockProperties) => boolean;

function parseVariant(value: unknown): ModelVariant | null {
  if (!isRecord(value)) return null;
  const model = stringField(value, 'model');
  if (model === null) return null;
  return {
    model,
    x: numberField(value, 'x') ?? 0,
    y: numberField(value, 'y') ?? 0,
    uvlock: booleanField(value, 'uvlock') ?? false,
    weight: Math.max(1, numberField(value, 'weight') ?? 1),
  };
}

function parseVariantList(value: unknown): ModelVariant[] {
  const list = Array.isArray(value) ? value : [value];
  return list.map(parseVariant).filter((variant) => variant !== null);
}

function valueText(value: unknown): string | null {
  if (typeof value === 'string') return value;
  if (typeof value === 'boolean' || typeof value === 'number') return String(value);
  return null;
}

function parseCondition(value: unknown): Condition {
  if (!isRecord(value)) return () => true;
  const or = value['OR'];
  if (Array.isArray(or)) {
    const parts = or.map(parseCondition);
    return (properties) => parts.some((part) => part(properties));
  }
  const and = value['AND'];
  if (Array.isArray(and)) {
    const parts = and.map(parseCondition);
    return (properties) => parts.every((part) => part(properties));
  }
  const checks: Condition[] = [];
  for (const [key, raw] of Object.entries(value)) {
    const text = valueText(raw);
    if (text === null) continue;
    const negated = text.startsWith('!');
    const allowed = new Set((negated ? text.slice(1) : text).split('|'));
    checks.push((properties) => {
      const actual = properties[key];
      const matches = actual !== undefined && allowed.has(actual);
      return negated ? !matches : matches;
    });
  }
  return (properties) => checks.every((check) => check(properties));
}

function variantKeyMatches(key: string, properties: BlockProperties, lenient: boolean): boolean {
  if (key === '' || key === 'normal' || key === 'all') return true;
  for (const pair of key.split(',')) {
    const equals = pair.indexOf('=');
    if (equals < 0) return false;
    const actual = properties[pair.slice(0, equals)];
    if (actual === undefined && lenient) continue;
    if (actual !== pair.slice(equals + 1)) return false;
  }
  return true;
}

export interface BlockStateDefinition {
  readonly parts: (properties: BlockProperties) => readonly (readonly ModelVariant[])[];
  readonly variants: readonly ModelVariant[];
  readonly multipart: boolean;
}

export function parseBlockStateDefinition(json: unknown): BlockStateDefinition | null {
  if (!isRecord(json)) return null;
  const variants = json['variants'];
  if (isRecord(variants)) {
    const entries = Object.entries(variants).map(([key, value]): [string, ModelVariant[]] => [key, parseVariantList(value)]);
    return {
      parts: (properties) => {
        const match = entries.find(([key]) => variantKeyMatches(key, properties, false)) ?? entries.find(([key]) => variantKeyMatches(key, properties, true));
        return match === undefined ? [] : [match[1]];
      },
      variants: entries.flatMap(([, list]) => list),
      multipart: false,
    };
  }
  const multipart = arrayField(json, 'multipart');
  if (multipart.length > 0) {
    const parts = multipart.filter(isRecord).map((part: JsonRecord): [Condition, ModelVariant[]] => [parseCondition(part['when']), parseVariantList(part['apply'])]);
    return {
      parts: (properties) => parts.filter(([condition]) => condition(properties)).map(([, list]) => list),
      variants: parts.flatMap(([, list]) => list),
      multipart: true,
    };
  }
  return null;
}

export function positionSeed(x: number, y: number, z: number): number {
  let hash = Math.imul(x, 3129871) ^ Math.imul(z, 116129781) ^ y;
  hash = Math.imul(hash, Math.imul(hash, 42317861) + 11);
  hash ^= hash >>> 16;
  return hash >>> 0;
}

export function pickWeighted<T extends { readonly weight: number }>(options: readonly T[], seed: number): T | null {
  if (options.length <= 1) return options[0] ?? null;
  const total = options.reduce((sum, option) => sum + option.weight, 0);
  let roll = seed % total;
  for (const option of options) {
    roll -= option.weight;
    if (roll < 0) return option;
  }
  return options[0] ?? null;
}
