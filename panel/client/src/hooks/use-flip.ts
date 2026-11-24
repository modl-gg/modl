import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, type RefObject } from 'react';

const FLIP_DURATION_MS = 180;
const FLIP_TIMING: KeyframeAnimationOptions = { duration: FLIP_DURATION_MS, easing: 'cubic-bezier(0.2, 0, 0, 1)' };
const REDUCED_MOTION_QUERY = '(prefers-reduced-motion: reduce)';
const KEY_SEPARATOR = '\u0000';

export interface FlipList<T extends HTMLElement> {
  ref: RefObject<T | null>;
  isSettling: () => boolean;
}

interface Measurement {
  container: HTMLElement;
  signature: string;
  offsets: Map<string, number>;
}

function layoutOffset(child: HTMLElement, container: HTMLElement): number {
  return child.offsetParent === container ? child.offsetTop : child.offsetTop - container.offsetTop;
}

function measure(container: HTMLElement, signature: string): Measurement {
  const keys = signature.split(KEY_SEPARATOR);
  const offsets = new Map<string, number>();
  Array.from(container.children).forEach((child, index) => {
    const key = keys[index];
    if (key !== undefined && child instanceof HTMLElement) offsets.set(key, layoutOffset(child, container));
  });
  return { container, signature, offsets };
}

function animateMoves(previous: Measurement, current: Measurement): boolean {
  let animated = false;
  const keys = current.signature.split(KEY_SEPARATOR);
  Array.from(current.container.children).forEach((child, index) => {
    const key = keys[index];
    const before = key === undefined ? undefined : previous.offsets.get(key);
    const after = key === undefined ? undefined : current.offsets.get(key);
    if (before === undefined || after === undefined || before === after) return;
    animated = true;
    child.animate([{ transform: `translateY(${String(before - after)}px)` }, { transform: 'translateY(0)' }], FLIP_TIMING);
  });
  return animated;
}

export function useFlipList<T extends HTMLElement>(order: readonly string[]): FlipList<T> {
  const containerRef = useRef<T>(null);
  const measurementRef = useRef<Measurement | null>(null);
  const settlingUntilRef = useRef(0);
  const signature = order.join(KEY_SEPARATOR);

  useLayoutEffect(() => {
    const container = containerRef.current;
    if (container === null) return;
    const current = measure(container, signature);
    const previous = measurementRef.current;
    const reordered = previous !== null && previous.container === container && previous.signature !== signature;
    if (reordered && !window.matchMedia(REDUCED_MOTION_QUERY).matches && animateMoves(previous, current)) {
      settlingUntilRef.current = performance.now() + FLIP_DURATION_MS;
    }
    measurementRef.current = current;
  }, [signature]);

  useEffect(() => {
    const container = containerRef.current;
    if (container === null) return undefined;
    const observer = new ResizeObserver(() => {
      const latest = measurementRef.current;
      if (latest?.container === container) measurementRef.current = measure(container, latest.signature);
    });
    observer.observe(container);
    return () => {
      observer.disconnect();
    };
  }, [signature]);

  const isSettling = useCallback(() => performance.now() < settlingUntilRef.current, []);

  return useMemo(() => ({ ref: containerRef, isSettling }), [isSettling]);
}
