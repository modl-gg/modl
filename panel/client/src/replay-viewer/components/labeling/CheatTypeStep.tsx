import React from 'react';
import { useTranslation } from 'react-i18next';
import type { CheatType } from '../../types/labeling';
import { CHEAT_LABELS, CHEAT_TYPES } from './labels';

interface CheatTypeStepProps {
  selected: CheatType[];
  onToggle: (type: CheatType) => void;
  onNext: () => void;
}

export const CheatTypeStep: React.FC<CheatTypeStepProps> = ({ selected, onToggle, onNext }) => {
  const { t } = useTranslation();
  return (
    <div>
      <h3 style={{ fontSize: 16, fontWeight: 500, marginBottom: 4 }}>
        {t('replay.labeling.cheatTypesTitle')}
      </h3>
      <p style={{ color: 'rgba(255,255,255,0.5)', fontSize: 13, marginBottom: 16 }}>
        {t('replay.labeling.cheatTypesHint')}
      </p>
      <div style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(2, 1fr)',
        gap: 8,
        marginBottom: 20,
      }}>
        {CHEAT_TYPES.map((type) => {
          const isSelected = selected.includes(type);
          return (
            <button
              key={type}
              onClick={() => onToggle(type)}
              style={{
                padding: '10px 12px',
                background: isSelected ? 'rgba(99, 102, 241, 0.3)' : 'rgba(255, 255, 255, 0.05)',
                border: `1px solid ${isSelected ? 'rgba(99, 102, 241, 0.5)' : 'rgba(255, 255, 255, 0.1)'}`,
                borderRadius: 8,
                color: 'white',
                fontSize: 13,
                cursor: 'pointer',
                transition: 'all 0.15s',
              }}
            >
              {t(CHEAT_LABELS[type].labelKey)}
            </button>
          );
        })}
      </div>
      <button
        onClick={onNext}
        disabled={selected.length === 0}
        style={{
          width: '100%',
          padding: '10px 16px',
          background: selected.length > 0 ? 'rgba(99, 102, 241, 0.6)' : 'rgba(255,255,255,0.1)',
          border: '1px solid rgba(99, 102, 241, 0.4)',
          borderRadius: 8,
          color: selected.length > 0 ? 'white' : 'rgba(255,255,255,0.3)',
          fontSize: 14,
          fontWeight: 500,
          cursor: selected.length > 0 ? 'pointer' : 'default',
        }}
      >
        {t('common.next')}
      </button>
    </div>
  );
};
