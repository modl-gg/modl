import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '@/hooks/use-auth';
import { useToast } from '@/hooks/use-toast';
import { useUpdateTicket } from '@/hooks/data/tickets';
import { REOPEN_ACTION } from './quick-actions';

export interface TicketReopen {
  text: string;
  setText: (text: string) => void;
  reopen: () => void;
  isReopening: boolean;
}

export function useTicketReopen(ticketId: string): TicketReopen {
  const { t } = useTranslation();
  const { toast } = useToast();
  const { user } = useAuth();
  const updateTicket = useUpdateTicket();
  const [text, setText] = useState('');

  const reopen = () => {
    const sent = text;
    updateTicket.mutateAsync({
      id: ticketId,
      author: user?.username ?? '',
      data: {
        locked: false,
        status: 'Open',
        newReply: {
          content: sent || t('ticket.reopen.defaultMessage'),
          action: REOPEN_ACTION,
        },
      },
    }).then(
      () => {
        setText((current) => (current === sent ? '' : current));
      },
      () => {
        toast({ title: t('toast.error'), description: t('ticket.reopen.failed'), variant: 'destructive' });
      },
    );
  };

  return { text, setText, reopen, isReopening: updateTicket.isPending };
}
