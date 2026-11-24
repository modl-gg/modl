const DAY_MS = 86_400_000;
const PERIOD_DAYS = /^(\d+)d$/;
const ISO_DAY = /^\d{4}-\d{2}-\d{2}$/;

function utcDayOf(date: Date): number {
  return Math.floor(date.getTime() / DAY_MS);
}

function dayNumber(isoDay: string): number | null {
  if (!ISO_DAY.test(isoDay)) return null;
  const time = Date.parse(`${isoDay}T00:00:00Z`);
  return Number.isNaN(time) ? null : Math.floor(time / DAY_MS);
}

function isoDayOf(day: number): string {
  return new Date(day * DAY_MS).toISOString().slice(0, 10);
}

function periodDays(period: string): number | null {
  const match = PERIOD_DAYS.exec(period);
  return match?.[1] === undefined ? null : Number(match[1]);
}

export function dailyRange(period: string, presentDays: readonly string[], today: Date): string[] {
  const todayNumber = utcDayOf(today);
  const present = presentDays.map(dayNumber).filter((day) => day !== null);
  const days = periodDays(period);
  const first = Math.min(days === null ? todayNumber - 1 : todayNumber - days, ...present);
  const last = Math.max(todayNumber, ...present);
  return Array.from({ length: last - first + 1 }, (_, offset) => isoDayOf(first + offset));
}

export function fillDailySeries<T extends { date: string }>(rows: readonly T[], period: string, today: Date, empty: (date: string) => T): T[] {
  if (rows.length === 0) return [];
  const byDate = new Map(rows.map((row) => [row.date, row]));
  return dailyRange(period, rows.map((row) => row.date), today).map((date) => byDate.get(date) ?? empty(date));
}
