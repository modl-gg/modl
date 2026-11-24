import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { PlayerLabel } from '../../types/labeling';
import { CHEAT_LABELS, VERDICT_STYLES } from './labels';

export type LabelSubmitHandler = (labels: PlayerLabel[]) => Promise<void>;

interface ReviewStepProps {
  labels: PlayerLabel[];
  onSubmit?: LabelSubmitHandler | undefined;
  onClose: () => void;
}

export const ReviewStep: React.FC<ReviewStepProps> = ({ labels, onSubmit, onClose }) => {
  const { t } = useTranslation();
  const [submitting, setSubmitting] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  const [submitFailed, setSubmitFailed] = useState(false);

  const handleSubmit = () => {
    if (!onSubmit) return;
    setSubmitting(true);
    setSubmitFailed(false);
    onSubmit(labels)
      .then(() => { setSubmitted(true); })
      .catch(() => { setSubmitFailed(true); })
      .finally(() => { setSubmitting(false); });
  };

  return (
    <div>
      <h3 style={{ fontSize: 16, fontWeight: 500, marginBottom: 16 }}>
        {t('replay.labeling.reviewTitle')}
      </h3>

      <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginBottom: 20, maxHeight: 260, overflowY: 'auto' }}>
        {labels.map((label) => (
          <div
            key={label.uuid}
            style={{
              background: 'rgba(255,255,255,0.05)',
              borderRadius: 8,
              padding: '10px 14px',
              fontSize: 13,
            }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
              <span style={{ fontWeight: 500 }}>{label.playerName}</span>
              <span style={{
                color: VERDICT_STYLES[label.verdict].color,
                fontWeight: 600,
                fontSize: 12,
                textTransform: 'uppercase',
              }}>
                {t(VERDICT_STYLES[label.verdict].labelKey)}
              </span>
            </div>
            {label.verdict === 'cheating' && label.cheats.length > 0 && (
              <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap', marginBottom: 4 }}>
                {label.cheats.map((c) => (
                  <span
                    key={c.type}
                    style={{
                      fontSize: 10,
                      padding: '1px 6px',
                      background: 'rgba(239, 68, 68, 0.2)',
                      borderRadius: 3,
                      color: '#fca5a5',
                    }}
                  >
                    {t(CHEAT_LABELS[c.type].labelKey)}
                  </span>
                ))}
              </div>
            )}
            <div style={{ color: 'rgba(255,255,255,0.4)', fontSize: 11 }}>
              {t('replay.labeling.confidenceValue', { value: label.confidence })}
              {label.notes && ` - ${label.notes}`}
            </div>
          </div>
        ))}
      </div>

      {/* Submit button */}
      {onSubmit && !submitted && (
        <button
          onClick={handleSubmit}
          disabled={submitting}
          style={{
            width: '100%',
            padding: '10px 16px',
            background: submitting ? 'rgba(99, 102, 241, 0.3)' : 'rgba(99, 102, 241, 0.6)',
            border: '1px solid rgba(99, 102, 241, 0.4)',
            borderRadius: 8,
            color: 'white',
            fontSize: 14,
            fontWeight: 500,
            cursor: submitting ? 'not-allowed' : 'pointer',
            marginBottom: 8,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            gap: 8,
          }}
        >
          {submitting ? (
            <>
              <span style={{
                display: 'inline-block',
                width: 14,
                height: 14,
                border: '2px solid rgba(255,255,255,0.3)',
                borderTopColor: 'white',
                borderRadius: '50%',
                animation: 'modl-spin 0.6s linear infinite',
              }} />
              {t('replay.labeling.submitting')}
            </>
          ) : (
            t('replay.labeling.submit')
          )}
        </button>
      )}

      {/* Success message */}
      {submitted && (
        <div style={{
          padding: '10px 14px',
          background: 'rgba(34, 197, 94, 0.15)',
          border: '1px solid rgba(34, 197, 94, 0.3)',
          borderRadius: 8,
          color: '#22c55e',
          fontSize: 13,
          marginBottom: 8,
          textAlign: 'center',
        }}>
          {t('replay.labeling.submitted')}
        </div>
      )}

      {/* Error message */}
      {submitFailed && (
        <div style={{
          padding: '10px 14px',
          background: 'rgba(239, 68, 68, 0.15)',
          border: '1px solid rgba(239, 68, 68, 0.3)',
          borderRadius: 8,
          color: '#ef4444',
          fontSize: 12,
          marginBottom: 8,
        }}>
          {t('replay.labeling.submitFailed')}
        </div>
      )}

      <button
        onClick={onClose}
        style={{
          width: '100%',
          padding: '10px 16px',
          background: 'rgba(255,255,255,0.05)',
          border: '1px solid rgba(255,255,255,0.1)',
          borderRadius: 8,
          color: 'rgba(255,255,255,0.7)',
          fontSize: 14,
          cursor: 'pointer',
        }}
      >
        {t('common.close')}
      </button>

      {/* Spinner animation */}
      <style>{`@keyframes modl-spin { to { transform: rotate(360deg); } }`}</style>
    </div>
  );
};
