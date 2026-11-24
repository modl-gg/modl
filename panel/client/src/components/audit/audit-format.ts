export const MINUTE_MS = 60 * 1000;
export const HOUR_MS = 60 * MINUTE_MS;
export const DAY_MS = 24 * HOUR_MS;

export function pickOption<T extends string>(options: readonly T[], value: string): T | undefined {
  return options.find((option) => option === value);
}

export const MAX_REASON_LENGTH = 500;

export function punishmentTypeBadgeVariant(banLike: boolean): 'destructive' | 'secondary' {
  return banLike ? 'destructive' : 'secondary';
}
