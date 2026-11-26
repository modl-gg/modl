import { useEffect, useState, useSyncExternalStore, type RefObject } from 'react';
import { parseGuiScaleChoice, type GuiScaleChoice, type GuiViewport } from '../gui/gui-scale';

const GUI_SCALE_STORAGE_KEY = 'modl.replay.guiScale';

export interface MeasuredViewport extends GuiViewport {
  readonly snapX: number;
  readonly snapY: number;
}

function snapOffset(position: number, pixelRatio: number): number {
  return (Math.round(position * pixelRatio) - position * pixelRatio) / pixelRatio;
}

function measure(element: HTMLElement): MeasuredViewport {
  const pixelRatio = window.devicePixelRatio > 0 ? window.devicePixelRatio : 1;
  const bounds = element.getBoundingClientRect();
  return {
    cssWidth: Math.floor(element.clientWidth),
    cssHeight: Math.floor(element.clientHeight),
    pixelRatio,
    snapX: snapOffset(bounds.left, pixelRatio),
    snapY: snapOffset(bounds.top, pixelRatio),
  };
}

function sameViewport(left: MeasuredViewport | null, right: MeasuredViewport): boolean {
  return left !== null
    && left.cssWidth === right.cssWidth
    && left.cssHeight === right.cssHeight
    && left.pixelRatio === right.pixelRatio
    && left.snapX === right.snapX
    && left.snapY === right.snapY;
}

export function useViewport(ref: RefObject<HTMLElement | null>): MeasuredViewport | null {
  const [viewport, setViewport] = useState<MeasuredViewport | null>(null);

  useEffect(() => {
    const element = ref.current;
    if (element === null) return undefined;
    let resolution: MediaQueryList | null = null;
    const update = () => {
      const next = measure(element);
      setViewport((current) => (sameViewport(current, next) ? current : next));
      resolution?.removeEventListener('change', update);
      resolution = window.matchMedia(`(resolution: ${String(next.pixelRatio)}dppx)`);
      resolution.addEventListener('change', update);
    };
    const observer = new ResizeObserver(update);
    observer.observe(element);
    const surface = element.closest('[role="dialog"]');
    surface?.addEventListener('animationend', update);
    window.addEventListener('resize', update);
    return () => {
      observer.disconnect();
      surface?.removeEventListener('animationend', update);
      window.removeEventListener('resize', update);
      resolution?.removeEventListener('change', update);
    };
  }, [ref]);

  return viewport;
}

function subscribeFullscreen(onChange: () => void): () => void {
  document.addEventListener('fullscreenchange', onChange);
  return () => {
    document.removeEventListener('fullscreenchange', onChange);
  };
}

function fullscreenActive(): boolean {
  return document.fullscreenElement instanceof Element;
}

export function useIsFullscreen(): boolean {
  return useSyncExternalStore(subscribeFullscreen, fullscreenActive);
}

export function fullscreenAvailable(): boolean {
  return 'requestFullscreen' in HTMLElement.prototype && document.fullscreenEnabled;
}

export function readGuiScaleChoice(): GuiScaleChoice {
  try {
    return parseGuiScaleChoice(window.localStorage.getItem(GUI_SCALE_STORAGE_KEY));
  } catch {
    return 'auto';
  }
}

export function storeGuiScaleChoice(choice: GuiScaleChoice): void {
  try {
    window.localStorage.setItem(GUI_SCALE_STORAGE_KEY, String(choice));
  } catch {
    return;
  }
}
