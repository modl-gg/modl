import { useTranslation } from 'react-i18next';
import { Notice } from '@/components/ui/notice';

export function InsecurePasswordNotice() {
  const { t } = useTranslation();

  if (window.isSecureContext) return null;

  return (
    <Notice variant="warning" title={t('auth.insecureTitle')}>
      {t('auth.insecureDesc')}
    </Notice>
  );
}
