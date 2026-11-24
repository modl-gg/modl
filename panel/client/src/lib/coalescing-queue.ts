export interface CoalescingTimers {
  now: () => number;
  schedule: (run: () => void, delayMs: number) => unknown;
  cancel: (handle: unknown) => void;
}

export interface CoalescingWindow {
  quietMs: number;
  maxWaitMs: number;
}

export interface CoalescingQueue<T> {
  add: (items: readonly T[]) => void;
  flush: () => void;
  discard: () => void;
  dispose: () => void;
}

interface PendingBatch<T> {
  readonly items: Map<string, T>;
  readonly startedAt: number;
  handle: unknown;
}

export function createCoalescingQueue<T>(
  keyOf: (item: T) => string,
  deliver: (items: T[]) => void,
  window: CoalescingWindow,
  timers: CoalescingTimers,
): CoalescingQueue<T> {
  let pending: PendingBatch<T> | null = null;
  let disposed = false;

  const flush = () => {
    if (pending === null) return;
    const { items, handle } = pending;
    pending = null;
    timers.cancel(handle);
    deliver([...items.values()]);
  };

  const discard = () => {
    if (pending !== null) timers.cancel(pending.handle);
    pending = null;
  };

  const reschedule = (batch: PendingBatch<T>) => {
    timers.cancel(batch.handle);
    const deadline = Math.min(timers.now() + window.quietMs, batch.startedAt + window.maxWaitMs);
    batch.handle = timers.schedule(flush, Math.max(0, deadline - timers.now()));
  };

  return {
    add: (items) => {
      if (disposed || items.length === 0) return;
      const batch = pending ?? { items: new Map<string, T>(), startedAt: timers.now(), handle: undefined };
      pending = batch;
      for (const item of items) {
        const key = keyOf(item);
        if (!batch.items.has(key)) batch.items.set(key, item);
      }
      reschedule(batch);
    },
    flush,
    discard,
    dispose: () => {
      disposed = true;
      discard();
    },
  };
}
