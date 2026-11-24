import React from 'react';
import { useTranslation } from 'react-i18next';
import { Info } from 'lucide-react';

interface InfoButtonProps {
  onClick: () => void;
  visible: boolean;
}

export const InfoButton: React.FC<InfoButtonProps> = ({ onClick, visible }) => {
  const { t } = useTranslation();
  return (
    <button
      onClick={onClick}
      style={{
        position: 'absolute',
        top: 16,
        left: 16,
        width: 40,
        height: 40,
        borderRadius: '50%',
        background: 'rgba(15, 15, 30, 0.7)',
        backdropFilter: 'blur(16px)',
        WebkitBackdropFilter: 'blur(16px)',
        border: '1px solid rgba(255, 255, 255, 0.1)',
        color: 'white',
        cursor: 'pointer',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        zIndex: 6,
        opacity: visible ? 1 : 0,
        transform: visible ? 'translateY(0)' : 'translateY(-8px)',
        transition: 'opacity 0.3s ease, transform 0.3s ease',
        pointerEvents: visible ? 'auto' : 'none',
      }}
      title={t('replay.info.open')}
      aria-label={t('replay.info.open')}
    >
      <Info size={18} />
    </button>
  );
};
