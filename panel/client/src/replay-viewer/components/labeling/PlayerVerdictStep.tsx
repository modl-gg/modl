import React from 'react';
import { useTranslation } from 'react-i18next';
import type { Verdict } from '../../types/labeling';
import { VERDICTS, VERDICT_STYLES } from './labels';

interface PlayerVerdictStepProps {
  playerName: string;
  onVerdict: (verdict: Verdict) => void;
}

export const PlayerVerdictStep: React.FC<PlayerVerdictStepProps> = ({ playerName, onVerdict }) => {
  const { t } = useTranslation();
  return (
    <div style={{ textAlign: 'center' }}>
      <h3 style={{ fontSize: 16, fontWeight: 500, marginBottom: 8 }}>
        {playerName}
      </h3>
      <p style={{ color: 'rgba(255,255,255,0.5)', fontSize: 13, marginBottom: 24 }}>
        {t('replay.labeling.verdictPrompt')}
      </p>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        {VERDICTS.map((verdict) => {
          const style = VERDICT_STYLES[verdict];
          return (
            <button
              key={verdict}
              onClick={() => onVerdict(verdict)}
              style={{
                padding: '12px 16px',
                background: style.background,
                border: style.border,
                borderRadius: 8,
                color: style.color,
                fontSize: 14,
                fontWeight: 600,
                cursor: 'pointer',
                transition: 'background 0.2s',
                width: '100%',
              }}
            >
              {t(style.labelKey)}
            </button>
          );
        })}
      </div>
    </div>
  );
};
