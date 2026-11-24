import { useTranslation } from 'react-i18next';
import { Loader2 } from 'lucide-react';
import { cn } from '@/lib/utils';

interface LoadingSpinnerProps {
  className?: string;
  label?: string;
}

export function LoadingSpinner({ className, label }: LoadingSpinnerProps) {
  const { t } = useTranslation();
  return (
    <span role="status" className="inline-flex">
      <Loader2 className={cn('animate-spin', className)} aria-hidden="true" />
      <span className="sr-only">{label ?? t('common.loading')}</span>
    </span>
  );
}
