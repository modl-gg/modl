import { useCallback, useEffect, useMemo, useRef, useState, type KeyboardEvent, type PointerEvent } from 'react';
import { useTranslation } from 'react-i18next';
import type { ReplayMarker } from '../../engine/ReplayPlayer';
import { GUI_COLORS } from '../gui/colors';
import { BUTTON_HEIGHT } from '../gui/GuiButton';
import { GuiCanvas, useGuiEnvironment } from '../gui/GuiCanvas';
import { GuiTooltipPanel } from '../gui/GuiTooltip';
import type { GuiPainter } from '../gui/painter';
import { formatClock } from '../hud/game-text';
import { seekStepFor } from '../stage/shortcuts';
import { clockText, handleX, MARKER_COLORS, MARKER_LABELS, markerX, nearestMarker, timeAtX } from './timeline-math';

const MARKER_TOP = 3;
const MARKER_HEIGHT = BUTTON_HEIGHT - 6;
const MARKER_ORDER = ['block_place', 'block_break', 'chat', 'damage', 'death', 'capture'] as const;
const TOOLTIP_GAP = 1;
export const TIME_LABEL_GAP = 4;

interface TimelineProps {
  readonly labelWidth: number;
  readonly width: number;
  readonly durationMs: number;
  readonly timeMs: number;
  readonly markers: readonly ReplayMarker[];
  readonly onSeek: (timeMs: number) => void;
  readonly onSeekBy: (deltaMs: number) => void;
}

interface Hover {
  readonly x: number;
  readonly timeMs: number;
  readonly marker: ReplayMarker | null;
}

export function Timeline({ width, labelWidth, durationMs, timeMs, markers, onSeek, onSeekBy }: TimelineProps) {
  const { t } = useTranslation();
  const { scale } = useGuiEnvironment();
  const [hover, setHover] = useState<Hover | null>(null);
  const [dragTime, setDragTime] = useState<number | null>(null);
  const [focused, setFocused] = useState(false);
  const pendingSeekRef = useRef<number | null>(null);
  const frameRef = useRef(0);
  useEffect(() => () => { cancelAnimationFrame(frameRef.current); }, []);
  const shownTime = dragTime ?? timeMs;
  const sortedMarkers = useMemo(() => [...markers].sort((left, right) => MARKER_ORDER.indexOf(left.kind) - MARKER_ORDER.indexOf(right.kind)), [markers]);

  const paint = useCallback((painter: GuiPainter) => {
    painter.stretched('widget/slider', 0, 0, width, BUTTON_HEIGHT);
    for (const marker of sortedMarkers) {
      painter.fill(markerX(marker.timeMs, width, durationMs), MARKER_TOP, 1, MARKER_HEIGHT, 0xff000000 | MARKER_COLORS[marker.kind]);
    }
    const highlighted = hover !== null || focused || dragTime !== null;
    painter.stretched(highlighted ? ['widget/slider_handle_highlighted', 'widget/slider_handle'] : 'widget/slider_handle', handleX(shownTime, width, durationMs), 0, 8, BUTTON_HEIGHT);
  }, [dragTime, durationMs, focused, hover, shownTime, sortedMarkers, width]);

  const guiX = (event: PointerEvent<HTMLDivElement>) => (event.clientX - event.currentTarget.getBoundingClientRect().left) / scale;

  const scheduleSeek = (target: number) => {
    pendingSeekRef.current = target;
    if (frameRef.current !== 0) return;
    frameRef.current = requestAnimationFrame(() => {
      frameRef.current = 0;
      if (pendingSeekRef.current !== null) onSeek(pendingSeekRef.current);
      pendingSeekRef.current = null;
    });
  };

  const pointerTime = (x: number) => nearestMarker(markers, x, width, durationMs)?.timeMs ?? timeAtX(x, width, durationMs);

  const handlePointerDown = (event: PointerEvent<HTMLDivElement>) => {
    if (event.button !== 0) return;
    event.currentTarget.setPointerCapture(event.pointerId);
    const target = pointerTime(guiX(event));
    setDragTime(target);
    scheduleSeek(target);
  };

  const handlePointerMove = (event: PointerEvent<HTMLDivElement>) => {
    const x = guiX(event);
    if (dragTime !== null) {
      const target = timeAtX(x, width, durationMs);
      setDragTime(target);
      scheduleSeek(target);
      return;
    }
    const marker = nearestMarker(markers, x, width, durationMs);
    setHover({ x, timeMs: marker?.timeMs ?? timeAtX(x, width, durationMs), marker });
  };

  const endDrag = () => {
    if (dragTime === null) return;
    onSeek(dragTime);
    setDragTime(null);
  };

  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const step = seekStepFor(event.key, event.shiftKey);
    if (event.key === 'Home') onSeek(0);
    else if (event.key === 'End') onSeek(durationMs);
    else if (step !== null) onSeekBy(step);
    else return;
    event.preventDefault();
  };

  return (
    <div className="flex shrink-0 items-center" style={{ gap: TIME_LABEL_GAP * scale }}>
      <div
        role="slider"
        tabIndex={0}
        aria-label={t('replayViewer.timeline')}
        aria-valuemin={0}
        aria-valuemax={Math.round(durationMs / 1000)}
        aria-valuenow={Math.round(shownTime / 1000)}
        aria-valuetext={clockText(shownTime, durationMs)}
        className="relative shrink-0 cursor-pointer touch-none outline-none"
        style={{ width: width * scale, height: BUTTON_HEIGHT * scale }}
        onPointerDown={handlePointerDown}
        onPointerMove={handlePointerMove}
        onPointerUp={endDrag}
        onPointerCancel={endDrag}
        onPointerLeave={() => { setHover(null); }}
        onKeyDown={handleKeyDown}
        onFocus={(event) => { setFocused(event.currentTarget.matches(':focus-visible')); }}
        onBlur={() => { setFocused(false); }}
      >
        <GuiCanvas width={width} height={BUTTON_HEIGHT} paint={paint} />
        {hover !== null && dragTime === null && <TimelineHover hover={hover} width={width} />}
      </div>
      <TimeLabel text={clockText(shownTime, durationMs)} width={labelWidth} />
    </div>
  );
}

function TimeLabel({ text, width }: { readonly text: string; readonly width: number }) {
  const paint = useCallback((painter: GuiPainter) => {
    painter.centeredText(text, width / 2, (BUTTON_HEIGHT - 8) / 2);
  }, [text, width]);
  return <GuiCanvas width={width} height={BUTTON_HEIGHT} paint={paint} />;
}

function TimelineHover({ hover, width }: { readonly hover: Hover; readonly width: number }) {
  const { t } = useTranslation();
  const { scale } = useGuiEnvironment();
  const lines = useMemo(() => {
    const clock = { text: formatClock(hover.timeMs), color: GUI_COLORS.white };
    return hover.marker === null ? [clock] : [{ text: t(MARKER_LABELS[hover.marker.kind]), color: MARKER_COLORS[hover.marker.kind] }, clock];
  }, [hover, t]);
  const left = Math.min(Math.max(0, hover.x - 12), Math.max(0, width - 60));
  return (
    <div className="pointer-events-none absolute z-10" style={{ left: left * scale, bottom: (BUTTON_HEIGHT + TOOLTIP_GAP) * scale }}>
      <GuiTooltipPanel lines={lines} />
    </div>
  );
}
