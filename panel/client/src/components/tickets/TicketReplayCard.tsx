import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { useOpenReplay } from '@/components/replays/use-replay-dialog';
import { replayIdFromReference } from '@/lib/replay-reference';

export function TicketReplayCard({ replayUrl }: { replayUrl: string }) {
  const { t } = useTranslation();
  const openReplay = useOpenReplay();
  const replayId = replayIdFromReference(replayUrl, window.location.origin);
  if (!replayId) return null;

  return (
    <Card className="shadow-card">
      <CardContent className="py-3">
        <div className="flex items-center justify-between gap-3">
          <p className="text-sm font-medium">{t('ticket.replay.title')}</p>
          <Button type="button" variant="outline" size="sm" onClick={() => { openReplay(replayId); }}>
            {t('ticket.replay.view')}
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}
