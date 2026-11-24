import { createContext, use, useCallback, useEffect, useLayoutEffect, useRef, useState, type PointerEvent as ReactPointerEvent, type ReactNode, type RefObject } from 'react';
import { createPortal } from 'react-dom';
import { useTranslation } from 'react-i18next';
import { ChevronDown, ChevronUp, RefreshCcw, User, X } from 'lucide-react';
import { IconAction } from '@/components/ui/icon-action';
import { cn } from '@/lib/utils';

interface Size {
  width: number;
  height: number;
}

interface Point {
  x: number;
  y: number;
}

type Edge = -1 | 0 | 1;

interface ResizeDirection {
  x: Edge;
  y: Edge;
  className: string;
}

const DEFAULT_SIZE: Size = { width: 780, height: 550 };
const DEFAULT_POSITION: Point = { x: 100, y: 100 };
const STAGGER_OFFSET_PX = 20;
const MINIMIZED_WIDTH_PX = 250;

const RESIZE_DIRECTIONS: readonly ResizeDirection[] = [
  { x: 1, y: 0, className: 'right-0 top-8 h-[calc(100%-16px)] w-3 cursor-e-resize z-20' },
  { x: 0, y: 1, className: 'bottom-0 left-0 h-3 w-full cursor-s-resize z-20' },
  { x: -1, y: 0, className: 'left-0 top-8 h-[calc(100%-16px)] w-3 cursor-w-resize z-20' },
  { x: 0, y: -1, className: '-top-1 left-0 h-2 w-full cursor-n-resize z-20' },
  { x: -1, y: 1, className: 'bottom-0 left-0 h-8 w-8 cursor-sw-resize z-30' },
  { x: 1, y: -1, className: 'right-0 top-8 h-8 w-8 cursor-ne-resize z-30' },
  { x: -1, y: -1, className: 'left-0 top-8 h-8 w-8 cursor-nw-resize z-30' },
  { x: 1, y: 1, className: 'bottom-0 right-0 h-8 w-8 cursor-se-resize z-30' },
];

const lastGeometry: { size: Size; position: Point } = { size: DEFAULT_SIZE, position: DEFAULT_POSITION };

export const WindowDockContext = createContext<HTMLElement | null>(null);

const DOCK_OFFSET_PX = 16;
const DOCK_HEIGHT_VARIABLE = '--window-dock-height';

function publishDockHeight(root: HTMLElement, element: HTMLElement): void {
  const height = element.offsetHeight;
  root.style.setProperty(DOCK_HEIGHT_VARIABLE, `${height === 0 ? 0 : height + DOCK_OFFSET_PX}px`);
}

export function WindowDock({ onElement }: { onElement: (element: HTMLDivElement | null) => void }) {
  const attach = useCallback((element: HTMLDivElement | null) => {
    onElement(element);
    if (element === null) return undefined;
    const root = document.documentElement;
    const observer = new ResizeObserver(() => { publishDockHeight(root, element); });
    observer.observe(element);
    return () => {
      observer.disconnect();
      root.style.removeProperty(DOCK_HEIGHT_VARIABLE);
      onElement(null);
    };
  }, [onElement]);
  return <div ref={attach} className="fixed bottom-4 right-4 flex flex-col-reverse items-end gap-2" />;
}

function clampToViewport(position: Point, size: Size): Point {
  return {
    x: Math.max(0, Math.min(position.x, window.innerWidth - size.width)),
    y: Math.max(0, Math.min(position.y, window.innerHeight - size.height)),
  };
}

function initialGeometry(stagger: number): { size: Size; position: Point } {
  const { size, position } = lastGeometry;
  const offset = stagger * STAGGER_OFFSET_PX;
  return { size, position: clampToViewport({ x: position.x + offset, y: position.y + offset }, size) };
}

function trackPointer(start: ReactPointerEvent, onMove: (delta: Point) => void, onEnd: () => void): AbortController {
  start.preventDefault();
  const origin = { x: start.clientX, y: start.clientY };
  const tracking = new AbortController();
  const options = { signal: tracking.signal };
  const end = () => {
    tracking.abort();
    onEnd();
  };
  document.addEventListener('pointermove', (event) => { onMove({ x: event.clientX - origin.x, y: event.clientY - origin.y }); }, options);
  document.addEventListener('pointerup', end, options);
  document.addEventListener('pointercancel', end, options);
  window.addEventListener('blur', end, options);
  return tracking;
}

function fittedToViewport(geometry: { size: Size; position: Point }, minSize: Size): { size: Size; position: Point } {
  const size = {
    width: Math.max(minSize.width, Math.min(geometry.size.width, window.innerWidth)),
    height: Math.max(minSize.height, Math.min(geometry.size.height, window.innerHeight)),
  };
  return { size, position: clampToViewport(geometry.position, size) };
}

function resizedAlong(edge: Edge, start: number, length: number, delta: number, minLength: number, viewportLength: number): { start: number; length: number } {
  if (edge === 0) return { start, length };
  const maxLength = edge === -1 ? start + length : viewportLength - start;
  const resized = Math.min(Math.max(minLength, length + edge * delta), Math.max(minLength, maxLength));
  return { start: edge === -1 ? start + length - resized : start, length: resized };
}

function isControl(target: EventTarget): boolean {
  return target instanceof Element && target.closest('button') !== null;
}

interface WindowControlsProps {
  minimized: boolean;
  toggleRef: RefObject<HTMLButtonElement | null>;
  onToggleMinimized: () => void;
  onClose: () => void;
  onRefresh?: (() => void) | undefined;
  isRefreshing: boolean;
}

function WindowControls({ minimized, toggleRef, onToggleMinimized, onClose, onRefresh, isRefreshing }: WindowControlsProps) {
  const { t } = useTranslation();
  const controlClass = 'h-5 w-5 min-w-0 p-0';
  return (
    <div className="ml-auto flex gap-1">
      {onRefresh !== undefined && (
        <IconAction
          icon={RefreshCcw}
          label={t('common.refresh')}
          className={controlClass}
          iconClassName={cn('h-3 w-3', isRefreshing && 'animate-spin')}
          disabled={isRefreshing}
          onClick={onRefresh}
        />
      )}
      <IconAction
        ref={toggleRef}
        icon={minimized ? ChevronUp : ChevronDown}
        label={minimized ? t('common.restore') : t('common.minimize')}
        className={controlClass}
        iconClassName="h-3 w-3"
        onClick={onToggleMinimized}
      />
      <IconAction icon={X} label={t('common.close')} destructive className={controlClass} iconClassName="h-3 w-3" onClick={onClose} />
    </div>
  );
}

interface ResizableWindowProps {
  title: string;
  layer: number;
  stagger: number;
  minSize: Size;
  onActivate: () => void;
  onClose: () => void;
  onRefresh?: (() => void) | undefined;
  isRefreshing?: boolean;
  children: ReactNode;
}

export function ResizableWindow({ title, layer, stagger, minSize, onActivate, onClose, onRefresh, isRefreshing = false, children }: ResizableWindowProps) {
  const [geometry, setGeometry] = useState(() => initialGeometry(stagger));
  const [minimized, setMinimized] = useState(false);
  const dock = use(WindowDockContext);
  const toggleRef = useRef<HTMLButtonElement>(null);
  const refocusToggleRef = useRef(false);
  const geometryRef = useRef(geometry);
  const pointerTrackingRef = useRef<AbortController | null>(null);
  useEffect(() => () => { pointerTrackingRef.current?.abort(); }, []);
  const { size, position } = geometry;

  useLayoutEffect(() => {
    if (!refocusToggleRef.current) return;
    refocusToggleRef.current = false;
    toggleRef.current?.focus();
  }, [minimized]);

  const toggleMinimized = () => {
    refocusToggleRef.current = toggleRef.current !== null && document.activeElement === toggleRef.current;
    setMinimized((current) => !current);
  };

  const update = (next: { size: Size; position: Point }) => {
    geometryRef.current = next;
    setGeometry(next);
  };

  useEffect(() => {
    const refit = () => {
      const fitted = fittedToViewport(geometryRef.current, minSize);
      geometryRef.current = fitted;
      setGeometry(fitted);
    };
    window.addEventListener('resize', refit);
    return () => { window.removeEventListener('resize', refit); };
  }, [minSize]);

  const remember = () => {
    lastGeometry.size = geometryRef.current.size;
    lastGeometry.position = geometryRef.current.position;
  };

  const track = (event: ReactPointerEvent, onMove: (delta: Point) => void) => {
    pointerTrackingRef.current?.abort();
    pointerTrackingRef.current = trackPointer(event, onMove, remember);
  };

  const startDrag = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (minimized || event.button !== 0 || isControl(event.target)) return;
    const start = geometryRef.current;
    track(event, (delta) => {
      update({ size: start.size, position: clampToViewport({ x: start.position.x + delta.x, y: start.position.y + delta.y }, start.size) });
    });
  };

  const startResize = (event: ReactPointerEvent<HTMLDivElement>, direction: ResizeDirection) => {
    if (event.button !== 0) return;
    event.stopPropagation();
    const start = geometryRef.current;
    track(event, (delta) => {
      const horizontal = resizedAlong(direction.x, start.position.x, start.size.width, delta.x, minSize.width, window.innerWidth);
      const vertical = resizedAlong(direction.y, start.position.y, start.size.height, delta.y, minSize.height, window.innerHeight);
      update({ size: { width: horizontal.length, height: vertical.length }, position: { x: horizontal.start, y: vertical.start } });
    });
  };

  const header = (
    <div
      className={cn(
        'flex h-7 touch-none select-none items-center justify-between bg-card px-3',
        minimized ? 'rounded-card border border-border/50 shadow-card hover:bg-card/90' : 'cursor-move border-b border-border',
      )}
      onPointerDown={startDrag}
    >
      <div className="mr-2 flex flex-1 items-center gap-1 truncate text-xs font-medium">
        <User className="h-3 w-3 shrink-0 text-muted-foreground" aria-hidden="true" />
        <span className="truncate">{title}</span>
      </div>
      <WindowControls
        minimized={minimized}
        toggleRef={toggleRef}
        onToggleMinimized={toggleMinimized}
        onClose={onClose}
        onRefresh={onRefresh}
        isRefreshing={isRefreshing}
      />
    </div>
  );

  if (minimized) {
    const docked = (
      <div role="dialog" aria-label={title} style={{ width: MINIMIZED_WIDTH_PX }} onPointerDownCapture={onActivate} onFocusCapture={onActivate}>
        {header}
      </div>
    );
    return dock === null ? null : createPortal(docked, dock);
  }

  return (
    <div
      role="dialog"
      aria-label={title}
      className="fixed rounded-card border border-border bg-background shadow-card-elevated"
      style={{
        top: position.y,
        left: position.x,
        width: size.width,
        height: size.height,
        zIndex: layer,
      }}
      onPointerDownCapture={onActivate}
      onFocusCapture={onActivate}
    >
      {header}
      <div className="scrollbar h-[calc(100%-28px)] w-full overflow-y-auto p-4">{children}</div>
      {RESIZE_DIRECTIONS.map((direction) => (
        <div
          key={direction.className}
          aria-hidden="true"
          className={cn('absolute touch-none', direction.className)}
          onPointerDown={(event) => { startResize(event, direction); }}
        />
      ))}
      <div className="pointer-events-none absolute bottom-0.5 right-0.5 z-30 flex h-3 w-3 flex-col items-end justify-end">
        <div className="mb-px h-[2px] w-[2px] bg-border" />
        <div className="mb-px h-[2px] w-[5px] bg-border" />
        <div className="h-[2px] w-[8px] bg-border" />
      </div>
    </div>
  );
}
