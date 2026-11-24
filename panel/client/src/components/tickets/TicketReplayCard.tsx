import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { replayIdFromReference, replayPagePath } from '@/lib/replay-reference';

export function TicketReplayCard({ replayUrl }: { replayUrl: string }) {
  const { t } = useTranslation();
  const replayId = replayIdFromReference(replayUrl, window.location.origin);
  if (!replayId) return null;

  return (
    <Card className="shadow-card">
      <CardContent className="py-3">
        <div className="flex items-center justify-between gap-3">
          <p className="text-sm font-medium">{t('ticket.replay.title')}</p>
          <Button asChild variant="outline" size="sm">
            <a href={replayPagePath(replayId)} target="_blank" rel="noopener noreferrer">
              {t('ticket.replay.view')}
            </a>
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}
