export function sameValue(left: unknown, right: unknown): boolean {
  if (Object.is(left, right)) return true;
  if (Array.isArray(left)) {
    return Array.isArray(right)
      && left.length === right.length
      && left.every((item, index) => sameValue(item, right[index]));
  }
  if (!isPlainRecord(left) || !isPlainRecord(right)) return false;
  const leftKeys = Object.keys(left).filter((key) => left[key] !== undefined);
  const rightKeys = Object.keys(right).filter((key) => right[key] !== undefined);
  return leftKeys.length === rightKeys.length
    && leftKeys.every((key) => Object.hasOwn(right, key) && sameValue(left[key], right[key]));
}

function isPlainRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export interface Draft<Snapshot, Value> {
  value: Value;
  base: Snapshot;
}

export interface Loaded<Snapshot, Value> {
  snapshot: Snapshot;
  value: Value;
}

export function editDraft<Snapshot, Value>(
  current: Draft<Snapshot, Value> | null,
  next: Value,
  loaded: Loaded<Snapshot, Value>,
  saving = false,
): Draft<Snapshot, Value> | null {
  if (!saving && sameValue(next, loaded.value)) return null;
  return { value: next, base: current?.base ?? loaded.snapshot };
}

export function settleDraft<Snapshot, Value>(
  current: Draft<Snapshot, Value> | null,
  saved: Draft<Snapshot, Value>,
  persisted: Loaded<Snapshot, Value>,
): Draft<Snapshot, Value> | null {
  if (current === null || current === saved) return null;
  return editDraft(null, current.value, persisted);
}

export interface SaveSourceStatus {
  isDirty: boolean;
  isValid: boolean;
}

export interface SaveSummary {
  isDirty: boolean;
  isValid: boolean;
}

export function summarizeSources(statuses: Iterable<SaveSourceStatus>): SaveSummary {
  let isDirty = false;
  let isValid = true;
  for (const status of statuses) {
    if (!status.isDirty) continue;
    isDirty = true;
    isValid &&= status.isValid;
  }
  return { isDirty, isValid };
}

export interface SavableSource {
  status: SaveSourceStatus;
  save: () => Promise<boolean>;
}

export async function saveDirtySources(sources: Iterable<SavableSource>): Promise<boolean> {
  for (const source of sources) {
    if (!source.status.isDirty) continue;
    if (!(await source.save())) return false;
  }
  return true;
}
