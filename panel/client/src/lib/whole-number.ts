const DIGITS_ONLY = /^\d+$/;

export function positiveWholeNumberOf(text: string): number | null {
  const trimmed = text.trim();
  if (!DIGITS_ONLY.test(trimmed)) return null;
  const value = Number(trimmed);
  return Number.isSafeInteger(value) && value >= 1 ? value : null;
}
