import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';

interface FailureNoticeProps {
  readonly message: string;
  readonly onRetry: (() => void) | null;
}

export function FailureNotice({ message, onRetry }: FailureNoticeProps) {
  const { t } = useTranslation();
  return (
    <div role="alert" className="absolute inset-0 z-30 flex flex-col items-center justify-center gap-4 bg-black px-6 text-center">
      <p className="max-w-md text-sm text-white/80">{message}</p>
      {onRetry !== null && (
        <Button type="button" variant="secondary" size="sm" onClick={onRetry}>
          {t('common.retry')}
        </Button>
      )}
    </div>
  );
}
