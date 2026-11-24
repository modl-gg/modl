import { useTranslation } from 'react-i18next';
import { CopyButton } from '@/components/ui/copy-button';

interface ClaimTicketNoticeProps {
  title: string;
  commandIntro: string;
  ticketId: string;
  accessKey: string | null;
}

export function ClaimTicketNotice({ title, commandIntro, ticketId, accessKey }: ClaimTicketNoticeProps) {
  const { t } = useTranslation();
  const command = accessKey ? `/tclaim ${ticketId} ${accessKey}` : null;

  return (
    <section className="space-y-2 rounded-md border p-3 text-sm">
      <h2 className="font-medium">{title}</h2>
      {command ? (
        <>
          <p className="text-muted-foreground">{commandIntro}</p>
          <div className="flex items-center gap-2">
            <code className="flex-1 min-w-0 break-all rounded-md bg-muted px-2 py-1.5 font-mono text-xs">{command}</code>
            <CopyButton value={command} label={t('ticketClaim.copyCommand')} failureMessage={t('ticketClaim.copyFailed')} />
          </div>
        </>
      ) : (
        <p className="text-muted-foreground">{t('ticketClaim.openFromLink')}</p>
      )}
    </section>
  );
}
