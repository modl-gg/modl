import { useState } from 'react';
import { Link } from 'wouter';
import { X } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { formatTimeAgo } from '@/utils/date-utils';
import { stripMarkdown } from '@/utils/markdown-utils';
import { ticketRoutePath } from '@/components/tickets/ticket-view';
import type { TicketSubscriptionUpdate } from '@/hooks/data/ticket-subscriptions';
import { DashboardMeta, DashboardSection, type DashboardFeedState } from './DashboardSection';

const INITIAL_VISIBLE = 2;
const LOAD_MORE_COUNT = 2;

interface AssignedTicketUpdatesSectionProps {
  feed: DashboardFeedState;
  updates: TicketSubscriptionUpdate[];
  onDismissTicket: (ticketId: string) => void;
}

export function AssignedTicketUpdatesSection({ feed, updates, onDismissTicket }: AssignedTicketUpdatesSectionProps) {
  const { t } = useTranslation();
  const [visibleCount, setVisibleCount] = useState(INITIAL_VISIBLE);
  const remaining = updates.length - visibleCount;

  return (
    <DashboardSection
      title={t('dashboard.assignedTickets.title')}
      count={updates.length}
      emptyText={t('dashboard.assignedTickets.empty')}
      isEmpty={updates.length === 0}
      feed={feed}
    >
      {updates.slice(0, visibleCount).map((update) => {
        const additional = update.additionalCount ?? 0;
        return (
          <li key={update.id} className="flex items-start gap-2 px-2 py-3">
            <Link href={ticketRoutePath(update.ticketId)} className="min-w-0 flex-1 rounded-md hover:opacity-80 focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-ring">
              <p className="truncate text-sm font-medium">{update.ticketTitle}</p>
              <p className="mt-0.5 line-clamp-2 text-sm text-muted-foreground">{stripMarkdown(update.replyContent)}</p>
              <DashboardMeta
                parts={[
                  update.replyBy,
                  formatTimeAgo(update.replyAt),
                  additional > 0 ? t('dashboard.assignedTickets.andMore', { count: additional }) : '',
                ]}
              />
            </Link>
            <Button
              variant="ghost"
              size="icon"
              className="h-7 w-7 shrink-0 text-muted-foreground"
              onClick={() => onDismissTicket(update.ticketId)}
              aria-label={t('dashboard.assignedTickets.dismissAll')}
            >
              <X className="h-4 w-4" />
            </Button>
          </li>
        );
      })}
      {remaining > 0 && (
        <li className="pt-2">
          <Button
            variant="ghost"
            size="sm"
            className="w-full text-muted-foreground"
            onClick={() => setVisibleCount((prev) => prev + LOAD_MORE_COUNT)}
          >
            {t('dashboard.assignedTickets.loadMore', { count: Math.min(remaining, LOAD_MORE_COUNT) })}
          </Button>
        </li>
      )}
    </DashboardSection>
  );
}
