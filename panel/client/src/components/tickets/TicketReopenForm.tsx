import { useTranslation } from 'react-i18next';
import { Loader2 } from 'lucide-react';
import { Textarea } from '@/components/ui/textarea';
import { Button } from '@/components/ui/button';
import type { TicketReopen } from './use-ticket-reopen';

interface TicketReopenFormProps {
  reopen: TicketReopen;
}

export function TicketReopenForm({ reopen }: TicketReopenFormProps) {
  const { t } = useTranslation();

  return (
    <div className="border rounded-md p-4 space-y-4">
      <p className="text-sm text-muted-foreground">{t('ticket.reopen.locked')}</p>

      <div className="space-y-2">
        <label htmlFor="ticket-reopen-message" className="text-sm font-medium">{t('ticket.reopen.label')}</label>
        <Textarea
          id="ticket-reopen-message"
          className="min-h-[80px] resize-none"
          placeholder={t('ticket.reopen.placeholder')}
          value={reopen.text}
          onChange={(event) => {
            reopen.setText(event.target.value);
          }}
        />

        <div className="flex justify-end">
          <Button variant="default" size="sm" disabled={reopen.isReopening} onClick={reopen.reopen}>
            {reopen.isReopening && <Loader2 className="h-4 w-4 mr-2 animate-spin" />}
            {t('ticket.reopen.submit')}
          </Button>
        </div>
      </div>
    </div>
  );
}
