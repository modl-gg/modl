import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { LoadFailure } from '@/components/ui/load-failure';
import { LoadingSpinner } from '@/components/ui/loading-spinner';

export interface ReportQuery {
  data: unknown;
  isPending: boolean;
  isError: boolean;
  refetch: () => Promise<unknown>;
}

export function ReportQueryGate({ queries, children }: { queries: readonly ReportQuery[]; children: ReactNode }) {
  const { t } = useTranslation();
  const failed = queries.filter((query) => query.isError && query.data === undefined);

  if (failed.length > 0) {
    return (
      <LoadFailure
        message={t('common.unknownError')}
        onRetry={() => {
          for (const query of failed) void query.refetch();
        }}
      />
    );
  }

  if (queries.some((query) => query.isPending)) {
    return (
      <div className="flex items-center justify-center py-8">
        <LoadingSpinner className="h-6 w-6 text-muted-foreground" />
      </div>
    );
  }

  return <>{children}</>;
}
