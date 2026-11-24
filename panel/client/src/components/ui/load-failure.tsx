import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';

interface LoadFailureProps {
  readonly message: string;
  readonly onRetry: () => void;
  readonly retrying?: boolean;
  readonly className?: string | undefined;
}

export function LoadFailure({ message, onRetry, retrying = false, className }: LoadFailureProps) {
  const { t } = useTranslation();
  return (
    <div role="alert" className={cn('flex flex-col items-center gap-2 py-6 text-center text-sm text-destructive-text', className)}>
      <p>{message}</p>
      <Button variant="outline" size="sm" disabled={retrying} onClick={onRetry}>{t('common.retry')}</Button>
    </div>
  );
}
