import { useTranslation } from 'react-i18next';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { keyByContent } from '@/components/ui/stable-keys';
import type { PanelTicketChatLine } from '@/hooks/data/tickets';

function chatLineContentKey(line: PanelTicketChatLine): string {
  return `${String(line.timestamp ?? '')}|${line.sender}|${line.text}`;
}

function ChatLine({ line }: { line: PanelTicketChatLine }) {
  const structured = line.sender !== '';
  const timeLabel = structured && line.timestamp !== null ? new Date(line.timestamp).toLocaleTimeString() : '';

  return (
    <div className="flex gap-2 py-0.5 hover:bg-muted/70 px-1 rounded">
      {timeLabel ? <span className="text-muted-foreground shrink-0">{timeLabel}</span> : null}
      {structured ? <span className="font-semibold text-primary">{line.sender}:</span> : null}
      <span className="min-w-0 text-foreground wrap-anywhere">{line.text}</span>
    </div>
  );
}

export function TicketChatLog({ lines }: { lines: readonly PanelTicketChatLine[] }) {
  const { t } = useTranslation();

  return (
    <Card className="shadow-card">
      <CardHeader className="pb-3">
        <CardTitle>{t('ticket.chatLog.title', { count: lines.length })}</CardTitle>
      </CardHeader>
      <CardContent>
        <div className="max-h-[300px] overflow-y-auto space-y-1 font-mono text-sm bg-muted/50 rounded-lg p-3">
          {keyByContent(lines, chatLineContentKey).map(({ key, item }) => (
            <ChatLine key={key} line={item} />
          ))}
        </div>
      </CardContent>
    </Card>
  );
}
