const DIGITS_ONLY = /^\d+$/;

export interface WholeNumberRange {
  readonly min: number;
  readonly max: number;
}

export function wholeNumberInRange(text: string, range: WholeNumberRange): number | null {
  const trimmed = text.trim();
  if (!DIGITS_ONLY.test(trimmed)) return null;
  const value = Number(trimmed);
  return Number.isSafeInteger(value) && value >= range.min && value <= range.max ? value : null;
}

export function positiveWholeNumberOf(text: string): number | null {
  return wholeNumberInRange(text, { min: 1, max: Number.MAX_SAFE_INTEGER });
}
