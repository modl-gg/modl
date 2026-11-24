import { useTranslation } from 'react-i18next';
import { AlertTriangle, Check, X } from 'lucide-react';

export type CheckState = 'passed' | 'warning' | 'failed';

export function CheckStatus({ state }: { state: CheckState }) {
  const { t } = useTranslation();
  switch (state) {
    case 'passed':
      return <Check role="img" className="h-5 w-5 shrink-0 text-success" aria-label={t('common.checkPassed')} />;
    case 'warning':
      return <AlertTriangle role="img" className="h-5 w-5 shrink-0 text-warning" aria-label={t('common.checkWarning')} />;
    case 'failed':
      return <X role="img" className="h-5 w-5 shrink-0 text-destructive-text" aria-label={t('common.checkFailed')} />;
  }
}
