import { type JsonRecord, isRecord } from '../format/guards.ts';

export { type JsonRecord, isRecord };

export function recordField(record: JsonRecord, key: string): JsonRecord | null {
  const value = record[key];
  return isRecord(value) ? value : null;
}

export function stringField(record: JsonRecord, key: string): string | null {
  const value = record[key];
  return typeof value === 'string' ? value : null;
}

export function numberField(record: JsonRecord, key: string): number | null {
  const value = record[key];
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

export function booleanField(record: JsonRecord, key: string): boolean | null {
  const value = record[key];
  return typeof value === 'boolean' ? value : null;
}

export function arrayField(record: JsonRecord, key: string): readonly unknown[] {
  const value = record[key];
  return Array.isArray(value) ? value : [];
}

export function numberTuple(value: unknown, length: number): number[] | null {
  if (!Array.isArray(value) || value.length !== length) return null;
  const numbers: number[] = [];
  for (const item of value) {
    if (typeof item !== 'number' || !Number.isFinite(item)) return null;
    numbers.push(item);
  }
  return numbers;
}

const UTF8 = new TextDecoder('utf-8');

export function parseJsonBytes(bytes: Uint8Array): unknown {
  try {
    return JSON.parse(UTF8.decode(bytes));
  } catch {
    return null;
  }
}
