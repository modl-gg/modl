import React, { useCallback, useState, useRef, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import type { PlaybackState, PlaybackRate } from '../types/PlaybackState';
import { formatTime } from './format';

interface TimelineControlsProps {
  state: PlaybackState;
  onPlay: () => void;
  onPause: () => void;
  onSeek: (timeMs: number) => void;
  onRateChange: (rate: PlaybackRate) => void;
  visible?: boolean;
}

const RATES: PlaybackRate[] = [0.25, 0.5, 1, 2, 4, 8];

export const TimelineControls: React.FC<TimelineControlsProps> = ({
  state,
  onPlay,
  onPause,
  onSeek,
  onRateChange,
  visible = true,
}) => {
  const { t } = useTranslation();
  const [showSpeed, setShowSpeed] = useState(false);
  const speedRef = useRef<HTMLDivElement>(null);

  const progress = state.totalDurationMs > 0
    ? (state.currentTimeMs / state.totalDurationMs) * 100
    : 0;

  const handleScrub = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      onSeek(Number(e.target.value));
    },
    [onSeek],
  );

  const handleSkip = useCallback(
    (deltaMs: number) => {
      const newTime = Math.max(0, Math.min(state.totalDurationMs, state.currentTimeMs + deltaMs));
      onSeek(newTime);
    },
    [onSeek, state.currentTimeMs, state.totalDurationMs],
  );

  // Close speed dropdown on outside click
  useEffect(() => {
    if (!showSpeed) return;
    const handleClick = (e: MouseEvent) => {
      const speedMenu = speedRef.current;
      if (speedMenu && !(e.target instanceof Node && speedMenu.contains(e.target))) {
        setShowSpeed(false);
      }
    };
    document.addEventListener('mousedown', handleClick);
    return () => document.removeEventListener('mousedown', handleClick);
  }, [showSpeed]);

  const btnStyle: React.CSSProperties = {
    background: 'none',
    border: 'none',
    color: 'white',
    fontSize: 14,
    cursor: 'pointer',
    padding: '4px 8px',
    lineHeight: 1,
    borderRadius: 4,
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    opacity: 0.85,
    transition: 'opacity 0.15s',
  };

  // Inline style tag for range input custom styling
  const scrubberStyleId = 'modl-scrubber-styles';
  useEffect(() => {
    if (document.getElementById(scrubberStyleId)) return;
    const style = document.createElement('style');
    style.id = scrubberStyleId;
    style.textContent = `
      .modl-scrubber {
        -webkit-appearance: none;
        appearance: none;
        height: 4px;
        border-radius: 2px;
        outline: none;
        cursor: pointer;
        background: transparent;
      }
      .modl-scrubber::-webkit-slider-runnable-track {
        height: 4px;
        border-radius: 2px;
        background: rgba(255,255,255,0.15);
      }
      .modl-scrubber::-webkit-slider-thumb {
        -webkit-appearance: none;
        appearance: none;
        width: 14px;
        height: 14px;
        border-radius: 50%;
        background: white;
        border: none;
        margin-top: -5px;
        box-shadow: 0 1px 4px rgba(0,0,0,0.4);
        cursor: pointer;
        transition: transform 0.1s;
      }
      .modl-scrubber::-webkit-slider-thumb:hover {
        transform: scale(1.2);
      }
      .modl-scrubber::-moz-range-track {
        height: 4px;
        border-radius: 2px;
        background: rgba(255,255,255,0.15);
        border: none;
      }
      .modl-scrubber::-moz-range-thumb {
        width: 14px;
        height: 14px;
        border-radius: 50%;
        background: white;
        border: none;
        box-shadow: 0 1px 4px rgba(0,0,0,0.4);
        cursor: pointer;
      }
      .modl-scrubber::-moz-range-progress {
        height: 4px;
        border-radius: 2px;
        background: rgba(99, 102, 241, 0.8);
      }
    `;
    document.head.appendChild(style);
  }, []);

  return (
    <div
      style={{
        position: 'absolute',
        bottom: 24,
        left: '50%',
        transform: visible ? 'translateX(-50%) translateY(0)' : 'translateX(-50%) translateY(12px)',
        background: 'rgba(15, 15, 30, 0.7)',
        backdropFilter: 'blur(24px)',
        WebkitBackdropFilter: 'blur(24px)',
        border: '1px solid rgba(255, 255, 255, 0.1)',
        borderRadius: 999,
        padding: '8px 20px',
        display: 'flex',
        alignItems: 'center',
        gap: 10,
        fontFamily: 'system-ui, sans-serif',
        color: 'white',
        fontSize: 13,
        zIndex: 5,
        opacity: visible ? 1 : 0,
        transition: 'opacity 0.3s ease, transform 0.3s ease',
        pointerEvents: visible ? 'auto' : 'none',
        userSelect: 'none',
      }}
      onMouseEnter={(e) => e.stopPropagation()}
    >
      {/* Skip back 5s */}
      <button
        onClick={() => handleSkip(-5000)}
        style={btnStyle}
        title={t('replay.timeline.back')}
        aria-label={t('replay.timeline.back')}
      >
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <polygon points="19,20 9,12 19,4" fill="currentColor" stroke="none" />
          <line x1="5" y1="4" x2="5" y2="20" />
        </svg>
      </button>

      {/* Play/Pause */}
      <button
        onClick={state.playing ? onPause : onPlay}
        style={{ ...btnStyle, fontSize: 18, padding: '4px 6px' }}
        title={state.playing ? t('replay.timeline.pause') : t('replay.timeline.play')}
        aria-label={state.playing ? t('replay.timeline.pause') : t('replay.timeline.play')}
      >
        {state.playing ? (
          <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor">
            <rect x="6" y="4" width="4" height="16" rx="1" />
            <rect x="14" y="4" width="4" height="16" rx="1" />
          </svg>
        ) : (
          <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor">
            <polygon points="6,4 20,12 6,20" />
          </svg>
        )}
      </button>

      {/* Skip forward 5s */}
      <button
        onClick={() => handleSkip(5000)}
        style={btnStyle}
        title={t('replay.timeline.forward')}
        aria-label={t('replay.timeline.forward')}
      >
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <polygon points="5,4 15,12 5,20" fill="currentColor" stroke="none" />
          <line x1="19" y1="4" x2="19" y2="20" />
        </svg>
      </button>

      {/* Scrubber */}
      <div style={{ flex: 1, minWidth: 120, position: 'relative' }}>
        {/* Progress fill overlay */}
        <div style={{
          position: 'absolute',
          top: '50%',
          left: 0,
          height: 4,
          width: `${progress}%`,
          background: 'rgba(99, 102, 241, 0.8)',
          borderRadius: 2,
          transform: 'translateY(-50%)',
          pointerEvents: 'none',
          zIndex: 0,
        }} />
        <input
          type="range"
          className="modl-scrubber"
          aria-label={t('replay.timeline.seek')}
          min={0}
          max={state.totalDurationMs}
          value={state.currentTimeMs}
          onChange={handleScrub}
          style={{ width: '100%', position: 'relative', zIndex: 1 }}
        />
      </div>

      {/* Time display */}
      <span style={{
        minWidth: 90,
        textAlign: 'center',
        fontSize: 12,
        fontFamily: 'monospace',
        opacity: 0.8,
        whiteSpace: 'nowrap',
      }}>
        {formatTime(state.currentTimeMs)} / {formatTime(state.totalDurationMs)}
      </span>

      {/* Speed selector */}
      <div ref={speedRef} style={{ position: 'relative' }}>
        <button
          onClick={() => setShowSpeed(!showSpeed)}
          style={{
            ...btnStyle,
            background: 'rgba(255, 255, 255, 0.08)',
            borderRadius: 6,
            padding: '3px 10px',
            fontSize: 12,
            fontFamily: 'system-ui, sans-serif',
          }}
          title={t('replay.timeline.speed')}
        >
          {t('replay.timeline.rate', { rate: state.rate })}
        </button>
        {showSpeed && (
          <div style={{
            position: 'absolute',
            bottom: '100%',
            right: 0,
            marginBottom: 8,
            background: 'rgba(15, 15, 30, 0.9)',
            backdropFilter: 'blur(16px)',
            WebkitBackdropFilter: 'blur(16px)',
            border: '1px solid rgba(255, 255, 255, 0.1)',
            borderRadius: 8,
            padding: 4,
            minWidth: 60,
          }}>
            {RATES.map((r) => (
              <button
                key={r}
                onClick={() => { onRateChange(r); setShowSpeed(false); }}
                style={{
                  display: 'block',
                  width: '100%',
                  padding: '5px 12px',
                  background: r === state.rate ? 'rgba(99, 102, 241, 0.3)' : 'none',
                  border: 'none',
                  borderRadius: 4,
                  color: 'white',
                  fontSize: 12,
                  cursor: 'pointer',
                  textAlign: 'center',
                }}
              >
                {t('replay.timeline.rate', { rate: r })}
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};
