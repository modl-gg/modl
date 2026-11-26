import { AsyncLocalStorage } from "node:async_hooks";

const scope = new AsyncLocalStorage<AbortSignal>();

export class CancelledError extends Error {}

export function cancellationError(signal: AbortSignal): Error {
  return signal.reason instanceof Error ? signal.reason : new CancelledError(String(signal.reason ?? "cancelled"));
}

export async function cancellable<T>(signal: AbortSignal, body: () => T | Promise<T>): Promise<T> {
  const parent = scope.getStore();
  return scope.run(parent ? AbortSignal.any([parent, signal]) : signal, body);
}

export function currentSignal(): AbortSignal | undefined {
  return scope.getStore();
}

export function throwIfCancelled(): void {
  const signal = scope.getStore();
  if (signal?.aborted) throw cancellationError(signal);
}

export function requestSignal(timeoutMs: number): AbortSignal {
  const timeout = AbortSignal.timeout(timeoutMs);
  const signal = scope.getStore();
  return signal ? AbortSignal.any([timeout, signal]) : timeout;
}

export async function cancellableDelay(ms: number): Promise<void> {
  const signal = scope.getStore();
  if (!signal) return new Promise((resolve) => setTimeout(resolve, ms));
  if (signal.aborted) throw cancellationError(signal);
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      signal.removeEventListener("abort", onAbort);
      resolve();
    }, ms);
    const onAbort = () => {
      clearTimeout(timer);
      reject(cancellationError(signal));
    };
    signal.addEventListener("abort", onAbort, { once: true });
  });
}
