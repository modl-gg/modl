import React, { useRef, useState, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import type { CheatType, DraftTimeRange } from '../../types/labeling';
import { formatTime } from '../format';
import { CHEAT_LABELS } from './labels';

interface TimeRangeStepProps {
  cheatType: CheatType;
  ranges: readonly DraftTimeRange[];
  currentTimeMs: number;
  totalDurationMs: number;
  onAddRange: () => void;
  onSetStart: (index: number, timeMs?: number) => void;
  onSetEnd: (index: number, timeMs?: number) => void;
  onRemoveRange: (index: number) => void;
  onNext: () => void;
}

interface DragState {
  rangeIndex: number;
  mode: 'move' | 'resize-start' | 'resize-end';
  startX: number;
  origStartMs: number;
  origEndMs: number;
}

export const TimeRangeStep: React.FC<TimeRangeStepProps> = ({
  cheatType,
  ranges,
  currentTimeMs,
  totalDurationMs,
  onAddRange,
  onSetStart,
  onSetEnd,
  onRemoveRange,
  onNext,
}) => {
  const { t } = useTranslation();
  const timelineRef = useRef<HTMLDivElement>(null);
  const [drag, setDrag] = useState<DragState | null>(null);

  const handlePointerDown = useCallback((e: React.PointerEvent<HTMLDivElement>, rangeIndex: number, mode: DragState['mode']) => {
    e.preventDefault();
    e.stopPropagation();
    e.currentTarget.setPointerCapture(e.pointerId);
    const range = ranges[rangeIndex];
    if (!range) return;
    setDrag({
      rangeIndex,
      mode,
      startX: e.clientX,
      origStartMs: range.startMs,
      origEndMs: range.endMs,
    });
  }, [ranges]);

  const handlePointerMove = useCallback((e: React.PointerEvent) => {
    if (!drag) return;
    const rect = timelineRef.current?.getBoundingClientRect();
    if (!rect || rect.width === 0) return;

    const deltaPx = e.clientX - drag.startX;
    const deltaMs = (deltaPx / rect.width) * totalDurationMs;

    switch (drag.mode) {
      case 'move': {
        const duration = drag.origEndMs - drag.origStartMs;
        let newStart = drag.origStartMs + deltaMs;
        let newEnd = drag.origEndMs + deltaMs;
        if (newStart < 0) { newStart = 0; newEnd = duration; }
        if (newEnd > totalDurationMs) { newEnd = totalDurationMs; newStart = totalDurationMs - duration; }
        onSetStart(drag.rangeIndex, Math.round(newStart));
        onSetEnd(drag.rangeIndex, Math.round(newEnd));
        break;
      }
      case 'resize-start': {
        const newStart = Math.max(0, Math.min(drag.origEndMs, drag.origStartMs + deltaMs));
        onSetStart(drag.rangeIndex, Math.round(newStart));
        break;
      }
      case 'resize-end': {
        const newEnd = Math.max(drag.origStartMs, Math.min(totalDurationMs, drag.origEndMs + deltaMs));
        onSetEnd(drag.rangeIndex, Math.round(newEnd));
        break;
      }
    }
  }, [drag, totalDurationMs, onSetStart, onSetEnd]);

  const handlePointerUp = useCallback(() => {
    setDrag(null);
  }, []);

  const HANDLE_WIDTH = 6;

  return (
    <div>
      <h3 style={{ fontSize: 16, fontWeight: 500, marginBottom: 4 }}>
        {t('replay.labeling.timeRangesTitle', { cheat: t(CHEAT_LABELS[cheatType].labelKey) })}
      </h3>
      <p style={{ color: 'rgba(255,255,255,0.5)', fontSize: 13, marginBottom: 4 }}>
        {t('replay.labeling.timeRangesHint')}
      </p>
      <p style={{ color: 'rgba(99, 102, 241, 0.8)', fontSize: 12, marginBottom: 16 }}>
        {t('replay.labeling.currentTime', { time: formatTime(currentTimeMs) })}
      </p>

      {/* Mini timeline */}
      <div
        ref={timelineRef}
        onPointerMove={handlePointerMove}
        onPointerUp={handlePointerUp}
        onPointerCancel={handlePointerUp}
        style={{
          height: 24,
          background: 'rgba(255,255,255,0.05)',
          borderRadius: 4,
          position: 'relative',
          marginBottom: 16,
          touchAction: 'none',
        }}
      >
        {ranges.map((r, i) => {
          const left = totalDurationMs > 0 ? (r.startMs / totalDurationMs) * 100 : 0;
          const width = totalDurationMs > 0 ? ((r.endMs - r.startMs) / totalDurationMs) * 100 : 0;
          const dragMode = drag?.rangeIndex === i ? drag.mode : null;
          return (
            <React.Fragment key={r.id}>
              {/* Range body - drag to move */}
              <div
                onPointerDown={(e) => handlePointerDown(e, i, 'move')}
                style={{
                  position: 'absolute',
                  left: `${left}%`,
                  width: `${Math.max(width, 0.5)}%`,
                  top: 0,
                  bottom: 0,
                  background: dragMode === 'move'
                    ? 'rgba(239, 68, 68, 0.6)'
                    : 'rgba(239, 68, 68, 0.4)',
                  borderRadius: 2,
                  cursor: dragMode === 'move' ? 'grabbing' : 'grab',
                  zIndex: 1,
                }}
              />
              {/* Left resize handle */}
              <div
                onPointerDown={(e) => handlePointerDown(e, i, 'resize-start')}
                style={{
                  position: 'absolute',
                  left: `${left}%`,
                  width: HANDLE_WIDTH,
                  marginLeft: -HANDLE_WIDTH / 2,
                  top: 0,
                  bottom: 0,
                  cursor: 'ew-resize',
                  zIndex: 2,
                  background: dragMode === 'resize-start'
                    ? 'rgba(34, 197, 94, 0.8)'
                    : 'rgba(34, 197, 94, 0.5)',
                  borderRadius: 2,
                }}
              />
              {/* Right resize handle */}
              <div
                onPointerDown={(e) => handlePointerDown(e, i, 'resize-end')}
                style={{
                  position: 'absolute',
                  left: `calc(${left + width}% - ${HANDLE_WIDTH / 2}px)`,
                  width: HANDLE_WIDTH,
                  top: 0,
                  bottom: 0,
                  cursor: 'ew-resize',
                  zIndex: 2,
                  background: dragMode === 'resize-end'
                    ? 'rgba(239, 68, 68, 0.8)'
                    : 'rgba(239, 68, 68, 0.6)',
                  borderRadius: 2,
                }}
              />
            </React.Fragment>
          );
        })}
        {/* Current time indicator */}
        <div style={{
          position: 'absolute',
          left: `${totalDurationMs > 0 ? (currentTimeMs / totalDurationMs) * 100 : 0}%`,
          top: 0,
          bottom: 0,
          width: 2,
          background: 'white',
          zIndex: 3,
          pointerEvents: 'none',
        }} />
      </div>

      {/* Range list */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginBottom: 16, maxHeight: 200, overflowY: 'auto' }}>
        {ranges.map((range, i) => (
          <div key={range.id} style={{
            background: 'rgba(255,255,255,0.05)',
            borderRadius: 6,
            padding: '6px 10px',
            fontSize: 13,
          }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 4 }}>
              <span style={{ color: 'rgba(255,255,255,0.5)' }}>#{i + 1}</span>
              <button
                onClick={() => onRemoveRange(i)}
                aria-label={t('replay.labeling.removeRange')}
                style={{
                  background: 'none',
                  border: 'none',
                  color: 'rgba(255,255,255,0.3)',
                  cursor: 'pointer',
                  fontSize: 14,
                }}
              >
                &times;
              </button>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
              <button
                onClick={() => onSetStart(i)}
                style={{
                  padding: '3px 6px',
                  background: 'rgba(34, 197, 94, 0.2)',
                  border: '1px solid rgba(34, 197, 94, 0.3)',
                  borderRadius: 4,
                  color: '#22c55e',
                  fontSize: 10,
                  cursor: 'pointer',
                }}
              >
                {t('replay.labeling.setStart')}
              </button>
              <span style={{ fontFamily: 'monospace', fontSize: 11 }}>
                {formatTime(range.startMs)}
              </span>
              <span style={{ color: 'rgba(255,255,255,0.3)' }}>-</span>
              <button
                onClick={() => onSetEnd(i)}
                style={{
                  padding: '3px 6px',
                  background: 'rgba(239, 68, 68, 0.2)',
                  border: '1px solid rgba(239, 68, 68, 0.3)',
                  borderRadius: 4,
                  color: '#ef4444',
                  fontSize: 10,
                  cursor: 'pointer',
                }}
              >
                {t('replay.labeling.setEnd')}
              </button>
              <span style={{ fontFamily: 'monospace', fontSize: 11 }}>
                {formatTime(range.endMs)}
              </span>
            </div>
          </div>
        ))}
      </div>

      <div style={{ display: 'flex', gap: 8 }}>
        <button
          onClick={onAddRange}
          style={{
            flex: 1,
            padding: '8px 12px',
            background: 'rgba(255,255,255,0.05)',
            border: '1px solid rgba(255,255,255,0.1)',
            borderRadius: 8,
            color: 'rgba(255,255,255,0.7)',
            fontSize: 13,
            cursor: 'pointer',
          }}
        >
          {t('replay.labeling.addRange')}
        </button>
        <button
          onClick={onNext}
          style={{
            flex: 1,
            padding: '8px 12px',
            background: 'rgba(99, 102, 241, 0.6)',
            border: '1px solid rgba(99, 102, 241, 0.4)',
            borderRadius: 8,
            color: 'white',
            fontSize: 13,
            fontWeight: 500,
            cursor: 'pointer',
          }}
        >
          {t('common.next')}
        </button>
      </div>
    </div>
  );
};
