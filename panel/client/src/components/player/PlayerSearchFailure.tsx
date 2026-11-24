import { useTranslation } from 'react-i18next';
import { LoadFailure } from '@/components/ui/load-failure';

export function PlayerSearchFailure({ onRetry, className }: { onRetry: () => void; className?: string }) {
  const { t } = useTranslation();
  return <LoadFailure message={t('search.failed')} onRetry={onRetry} className={className} />;
}
