import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { LabelingWizard } from './LabelingWizard';
import type { LabelSubmitHandler } from './labeling/ReviewStep';
import { formatTime } from './format';

const SHORTCUTS = [
  { key: 'space', keyLabelKey: 'replay.info.keySpace', actionKey: 'replay.info.playPause' },
  { key: 'left', keyLabel: '\u2190', actionKey: 'replay.info.rewind' },
  { key: 'right', keyLabel: '\u2192', actionKey: 'replay.info.forward' },
] as const;

interface InfoSidebarProps {
  replayId?: string | undefined;
  duration: number;
  playerCount: number;
  playerNames: string[];
  onClose: () => void;
  players: { uuid: string; playerName: string }[];
  currentTimeMs: number;
  totalDurationMs: number;
  onLabelSubmit?: LabelSubmitHandler | undefined;
  labeled?: boolean | undefined;
}

export const InfoSidebar: React.FC<InfoSidebarProps> = ({
  replayId,
  duration,
  playerCount,
  playerNames,
  onClose,
  players,
  currentTimeMs,
  totalDurationMs,
  onLabelSubmit,
  labeled,
}) => {
  const { t } = useTranslation();
  const [showLabeling, setShowLabeling] = useState(false);

  return (
    <div style={{
      position: 'absolute',
      top: 16,
      left: 16,
      bottom: 80,
      width: 300,
      background: 'rgba(15, 15, 30, 0.5)',
      backdropFilter: 'blur(16px)',
      WebkitBackdropFilter: 'blur(16px)',
      border: '1px solid rgba(255, 255, 255, 0.08)',
      borderRadius: 12,
      overflowY: 'auto',
      fontFamily: 'system-ui, sans-serif',
      color: 'white',
      fontSize: 13,
      zIndex: 5,
      display: 'flex',
      flexDirection: 'column',
    }}>
      {/* Close button */}
      <button
        onClick={onClose}
        aria-label={t('common.close')}
        style={{
          position: 'absolute',
          top: 8,
          right: 12,
          background: 'none',
          border: 'none',
          color: 'rgba(255, 255, 255, 0.5)',
          fontSize: 18,
          cursor: 'pointer',
          lineHeight: 1,
          zIndex: 1,
        }}
      >
        &times;
      </button>

      {!showLabeling ? (
        <div style={{ padding: '14px 16px', display: 'flex', flexDirection: 'column', gap: 14 }}>
          {/* Replay Info */}
          <div>
            <div style={{ fontSize: 10, textTransform: 'uppercase', letterSpacing: '0.05em', opacity: 0.5, marginBottom: 8 }}>
              {t('replay.info.title')}
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 10, fontSize: 13 }}>
              <div>
                <span style={{ color: 'rgba(255,255,255,0.5)', fontSize: 10, textTransform: 'uppercase', letterSpacing: '0.05em' }}>
                  {t('replay.info.replayId')}
                </span>
                <div style={{ marginTop: 2, fontFamily: 'monospace', fontSize: 12 }}>
                  {replayId || t('replay.info.localFile')}
                </div>
              </div>
              <div>
                <span style={{ color: 'rgba(255,255,255,0.5)', fontSize: 10, textTransform: 'uppercase', letterSpacing: '0.05em' }}>
                  {t('replay.info.duration')}
                </span>
                <div style={{ marginTop: 2 }}>{formatTime(duration)}</div>
              </div>
              <div>
                <span style={{ color: 'rgba(255,255,255,0.5)', fontSize: 10, textTransform: 'uppercase', letterSpacing: '0.05em' }}>
                  {t('replay.info.players', { total: playerCount })}
                </span>
                <div style={{ marginTop: 4, display: 'flex', flexWrap: 'wrap', gap: 4 }}>
                  {playerNames.map((name) => (
                    <span
                      key={name}
                      style={{
                        background: 'rgba(255,255,255,0.08)',
                        borderRadius: 4,
                        padding: '2px 6px',
                        fontSize: 11,
                      }}
                    >
                      {name}
                    </span>
                  ))}
                </div>
              </div>
            </div>
          </div>

          {/* Divider */}
          <div style={{ borderTop: '1px solid rgba(255,255,255,0.08)' }} />

          {/* Keybind Hints */}
          <div>
            <div style={{ fontSize: 10, textTransform: 'uppercase', letterSpacing: '0.05em', opacity: 0.5, marginBottom: 8 }}>
              {t('replay.info.shortcuts')}
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
              {SHORTCUTS.map((shortcut) => (
                <div key={shortcut.key} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <span style={{
                    background: 'rgba(255,255,255,0.1)',
                    borderRadius: 4,
                    padding: '2px 8px',
                    fontSize: 11,
                    fontFamily: 'monospace',
                    minWidth: 44,
                    textAlign: 'center',
                  }}>
                    {'keyLabelKey' in shortcut ? t(shortcut.keyLabelKey) : shortcut.keyLabel}
                  </span>
                  <span style={{ fontSize: 12, opacity: 0.7 }}>{t(shortcut.actionKey)}</span>
                </div>
              ))}
            </div>
          </div>

          {/* Divider */}
          <div style={{ borderTop: '1px solid rgba(255,255,255,0.08)' }} />

          {/* Label Button */}
          {labeled ? (
            <div
              style={{
                width: '100%',
                padding: '8px 14px',
                background: 'rgba(255,255,255,0.05)',
                border: '1px solid rgba(255,255,255,0.1)',
                borderRadius: 8,
                color: 'rgba(255,255,255,0.4)',
                fontSize: 13,
                fontWeight: 500,
                textAlign: 'center',
              }}
            >
              {t('replay.info.alreadyLabeled')}
            </div>
          ) : (
            <button
              onClick={() => setShowLabeling(true)}
              style={{
                width: '100%',
                padding: '8px 14px',
                background: 'rgba(99, 102, 241, 0.6)',
                border: '1px solid rgba(99, 102, 241, 0.4)',
                borderRadius: 8,
                color: 'white',
                fontSize: 13,
                fontWeight: 500,
                cursor: 'pointer',
                transition: 'background 0.2s',
              }}
            >
              {t('replay.info.labelReplay')}
            </button>
          )}
        </div>
      ) : (
        <LabelingWizard
          players={players}
          currentTimeMs={currentTimeMs}
          totalDurationMs={totalDurationMs}
          onClose={() => setShowLabeling(false)}
          onSubmit={onLabelSubmit}
        />
      )}
    </div>
  );
};
