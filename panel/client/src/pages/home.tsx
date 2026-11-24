import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  RefreshCw,
  Sun,
  Moon
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { useTheme } from 'next-themes';
import {
  useRecentTickets,
  useRecentPunishments,
  useAssignedTicketUpdates,
  useMarkTicketAsRead
} from '@/hooks/use-data';
import { useToast } from '@/hooks/use-toast';
import { usePermissions, PERMISSIONS } from '@/hooks/use-permissions';
import PageContainer from '@/components/layout/PageContainer';
import type { DashboardFeedState } from '@/components/dashboard/DashboardSection';
import { RecentTicketsSection } from '@/components/dashboard/RecentTicketsSection';
import { RecentPunishmentsSection } from '@/components/dashboard/RecentPunishmentsSection';
import { AssignedTicketUpdatesSection } from '@/components/dashboard/AssignedTicketUpdatesSection';

const FEED_SKELETON_IDS = ['tickets', 'punishments'] as const;
const MIN_REFRESH_SPIN_MS = 800;

async function waitAtLeast(ms: number): Promise<void> {
  await new Promise<void>((resolve) => {
    setTimeout(resolve, ms);
  });
}

interface FeedQuery {
  data: unknown;
  isError: boolean;
  isFetching: boolean;
  refetch: () => Promise<unknown>;
}

function feedStateOf({ data, isError, isFetching, refetch }: FeedQuery): DashboardFeedState {
  return { failed: isError && data === undefined, retrying: isFetching, onRetry: () => { void refetch(); } };
}

function DashboardLoadingSkeleton() {
  return (
    <>
      <Card className="shadow-card">
        <CardContent className="p-5 space-y-3">
          <Skeleton className="h-5 w-48" />
          <Skeleton className="h-4 w-full" />
          <Skeleton className="h-4 w-3/4" />
        </CardContent>
      </Card>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        {FEED_SKELETON_IDS.map((skeletonId) => (
          <Card key={skeletonId} className="shadow-card">
            <CardContent className="p-5 space-y-4">
              <Skeleton className="h-5 w-40" />
              <Skeleton className="h-16 w-full" />
              <Skeleton className="h-16 w-full" />
              <Skeleton className="h-16 w-5/6" />
            </CardContent>
          </Card>
        ))}
      </div>
    </>
  );
}

const Home = () => {
  const [isSpinning, setIsSpinning] = useState(false);
  const { toast } = useToast();
  const { resolvedTheme, setTheme } = useTheme();
  const { t } = useTranslation();

  const { hasPermission, isPermissionsLoading } = usePermissions();
  const canViewDashboardFeeds = hasPermission(PERMISSIONS.ADMIN_AUDIT_VIEW_DASHBOARD);
  const feedGate = { enabled: canViewDashboardFeeds };

  const recentTickets = useRecentTickets(3, feedGate);
  const recentPunishments = useRecentPunishments(5, feedGate);
  const assignedUpdates = useAssignedTicketUpdates(10);
  const isInitialDashboardLoading = isPermissionsLoading
    || assignedUpdates.isLoading
    || (canViewDashboardFeeds && (recentTickets.isLoading || recentPunishments.isLoading));

  const { mutate: markTicketAsRead } = useMarkTicketAsRead();

  const refreshDashboard = async () => {
    setIsSpinning(true);
    const feedRefetches = canViewDashboardFeeds ? [recentTickets.refetch(), recentPunishments.refetch()] : [];
    await Promise.all([
      Promise.all([...feedRefetches, assignedUpdates.refetch()]),
      waitAtLeast(MIN_REFRESH_SPIN_MS),
    ]);
    setIsSpinning(false);
  };

  const handleRefreshData = () => {
    void refreshDashboard();
  };

  const handleDismissTicket = (ticketId: string) => {
    markTicketAsRead(ticketId, {
      onError: () => {
        toast({ title: t('toast.error'), description: t('dashboard.assignedTickets.dismissFailed'), variant: 'destructive' });
      },
    });
  };

  return (
    <PageContainer>
      <div className="flex flex-col space-y-4">
        <div className="flex justify-between items-center">
          <h2 className="text-xl font-semibold">{t('dashboard.title')}</h2>
          <div className="flex items-center gap-1">
            <Button
              variant="ghost"
              size="icon"
              className="text-muted-foreground"
              onClick={handleRefreshData}
              disabled={isSpinning || isInitialDashboardLoading}
              aria-label={t('common.refresh')}
            >
              <RefreshCw className={`h-5 w-5 ${isSpinning ? 'animate-spin' : ''}`} />
            </Button>
            <Button
              variant="ghost"
              size="icon"
              className="text-muted-foreground"
              onClick={() => setTheme(resolvedTheme === 'dark' ? 'light' : 'dark')}
              aria-label={t('home.toggleTheme')}
            >
              {resolvedTheme === 'dark' ? <Sun className="h-5 w-5" /> : <Moon className="h-5 w-5" />}
            </Button>
          </div>
        </div>

        {isInitialDashboardLoading ? (
          <DashboardLoadingSkeleton />
        ) : (
          <>
            <AssignedTicketUpdatesSection
              feed={feedStateOf(assignedUpdates)}
              updates={assignedUpdates.data ?? []}
              onDismissTicket={handleDismissTicket}
            />

            {canViewDashboardFeeds && (
              <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
                <RecentTicketsSection
                  feed={feedStateOf(recentTickets)}
                  tickets={recentTickets.data ?? []}
                />
                <RecentPunishmentsSection
                  feed={feedStateOf(recentPunishments)}
                  punishments={recentPunishments.data ?? []}
                />
              </div>
            )}
          </>
        )}
      </div>
    </PageContainer>
  );
};

export default Home;
