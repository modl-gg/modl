import type { JsonObject, JsonValue } from '@bufbuild/protobuf';
import { timestampDate, type Timestamp } from '@bufbuild/protobuf/wkt';

export const tsToDate = (t?: Timestamp): Date | null => (t ? timestampDate(t) : null);

export const tsToMillis = (t?: Timestamp): number | null => (t ? timestampDate(t).getTime() : null);

// bigint → number ONLY for values known to be < 2^53 (counts, durations, ids that fit).
// Never blindly Number() a bigint that could exceed Number.MAX_SAFE_INTEGER.
export const toNum = (v: bigint): number => Number(v);

export const tsToIso = (t?: Timestamp): string | null => tsToDate(t)?.toISOString() ?? null;

export function definedJsonFields(fields: Readonly<Record<string, JsonValue | undefined>>): JsonObject {
  return Object.fromEntries(
    Object.entries(fields).filter((entry): entry is [string, JsonValue] => entry[1] !== undefined),
  );
}
