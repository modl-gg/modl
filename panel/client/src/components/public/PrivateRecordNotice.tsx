import { useTranslation } from 'react-i18next';

export function PrivateRecordNotice() {
  const { t } = useTranslation();
  return <p className="text-sm text-muted-foreground">{t('playerTicket.linkRequiredDesc')}</p>;
}
