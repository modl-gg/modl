import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { LoadFailure } from '@/components/ui/load-failure';

export interface DashboardFeedState {
  failed: boolean;
  retrying: boolean;
  onRetry: () => void;
}

interface DashboardSectionProps {
  title: string;
  count?: number | undefined;
  emptyText: string;
  isEmpty: boolean;
  feed: DashboardFeedState;
  children: ReactNode;
}

function DashboardSectionBody({ emptyText, isEmpty, feed, children }: Omit<DashboardSectionProps, 'title' | 'count'>) {
  const { t } = useTranslation();
  if (feed.failed) return <LoadFailure message={t('dashboard.loadFailed')} retrying={feed.retrying} onRetry={feed.onRetry} />;
  if (isEmpty) return <p className="py-8 text-center text-sm text-muted-foreground">{emptyText}</p>;
  return <ul className="-mx-2 divide-y">{children}</ul>;
}

export function DashboardSection({ title, count, emptyText, isEmpty, feed, children }: DashboardSectionProps) {
  return (
    <Card className="shadow-card">
      <CardHeader className="pb-3">
        <CardTitle className="flex items-baseline gap-2">
          {title}
          {count !== undefined && count > 0 && (
            <span className="text-sm font-normal text-muted-foreground tabular-nums">{count}</span>
          )}
        </CardTitle>
      </CardHeader>
      <CardContent>
        <DashboardSectionBody emptyText={emptyText} isEmpty={isEmpty} feed={feed}>{children}</DashboardSectionBody>
      </CardContent>
    </Card>
  );
}

export function DashboardMeta({ parts }: { parts: readonly string[] }) {
  return <p className="mt-1 text-xs text-muted-foreground">{parts.filter((part) => part !== '').join(' · ')}</p>;
}
