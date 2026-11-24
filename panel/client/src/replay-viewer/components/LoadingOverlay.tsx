import React from 'react';
import { useTranslation } from 'react-i18next';

interface LoadingOverlayProps {
  message?: string;
}

export const LoadingOverlay: React.FC<LoadingOverlayProps> = ({ message }) => {
  const { t } = useTranslation();
  return (
    <div style={{
      position: 'absolute',
      inset: 0,
      display: 'flex',
      flexDirection: 'column',
      alignItems: 'center',
      justifyContent: 'center',
      backgroundColor: 'rgba(0, 0, 0, 0.7)',
      color: 'white',
      zIndex: 10,
      fontFamily: 'system-ui, sans-serif',
    }}>
      <div style={{
        width: 40,
        height: 40,
        border: '3px solid rgba(255, 255, 255, 0.3)',
        borderTopColor: 'white',
        borderRadius: '50%',
        animation: 'spin 0.8s linear infinite',
      }} />
      <p style={{ marginTop: 16, fontSize: 14 }}>{message ?? t('replay.viewer.loading')}</p>
      <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
    </div>
  );
};
