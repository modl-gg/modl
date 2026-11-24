import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { LoadingSpinner } from '@/components/ui/loading-spinner';
import { Notice } from '@/components/ui/notice';

interface StepLoadStateProps {
  isError: boolean;
  onRetry: () => void;
}

export function StepLoadState({ isError, onRetry }: StepLoadStateProps) {
  const { t } = useTranslation();

  if (!isError) {
    return (
      <div className="flex justify-center py-6">
        <LoadingSpinner className="h-6 w-6 text-muted-foreground" />
      </div>
    );
  }
  return (
    <div className="space-y-3">
      <Notice variant="error" announce>{t('pages.setup.stepLoadFailed')}</Notice>
      <Button type="button" variant="outline" size="sm" onClick={onRetry}>{t('common.retry')}</Button>
    </div>
  );
}
