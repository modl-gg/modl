import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Ban, Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { CopyButton } from '@/components/ui/copy-button';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { IconAction } from '@/components/ui/icon-action';
import { Input } from '@/components/ui/input';
import { LoadingSpinner } from '@/components/ui/loading-spinner';
import { Notice } from '@/components/ui/notice';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { StatusBadge } from '@/components/ui/status-badge';
import { useConfirmation } from '@/hooks/use-confirmation';
import { useToast } from '@/hooks/use-toast';
import {
  DEFAULT_SHARE_EXPIRY,
  SHARE_EXPIRY_CHOICES,
  useCreateReplayShare,
  useReplayShares,
  useRevokeReplayShare,
  type CreatedReplayShare,
  type ReplayShare,
  type ShareExpiryChoice,
} from '@/hooks/data/replay-shares';
import { apiErrorCode } from '@/lib/http-error';
import { isShareActive, isShareRevoked } from '@/lib/replay-share-state';
import { formatDateWithTime } from '@/utils/date-utils';

const EXPIRY_LABEL_KEYS: Readonly<Record<ShareExpiryChoice, string>> = {
  'one-day': 'replayViewer.share.expiry.oneDay',
  'seven-days': 'replayViewer.share.expiry.sevenDays',
  'thirty-days': 'replayViewer.share.expiry.thirtyDays',
  never: 'replayViewer.share.expiry.never',
};

const CREATE_FAILURE_KEYS: ReadonlyMap<string, string> = new Map([
  ['REPLAY_SHARE_LIMIT', 'replayViewer.share.limitReached'],
  ['REPLAY_NOT_FOUND', 'replayViewer.failure.replayMissing'],
  ['RATE_LIMITED', 'replayViewer.share.rateLimited'],
]);

function isExpiryChoice(value: string): value is ShareExpiryChoice {
  return SHARE_EXPIRY_CHOICES.some((choice) => choice === value);
}

function CreatedLink({ created }: { created: CreatedReplayShare }) {
  const { t } = useTranslation();
  return (
    <div className="space-y-2">
      <div className="flex items-center gap-2">
        <Input
          readOnly
          value={created.url}
          aria-label={t('replayViewer.share.link')}
          className="font-mono text-xs"
          onFocus={(event) => { event.currentTarget.select(); }}
        />
        <CopyButton value={created.url} label={t('replayViewer.copyLink')} failureMessage={t('replayViewer.copyLinkFailed')} />
      </div>
      <p className="text-xs text-muted-foreground">{t('replayViewer.share.shownOnce')}</p>
    </div>
  );
}

function ShareStateBadge({ share, now }: { share: ReplayShare; now: number }) {
  const { t } = useTranslation();
  if (share.revokedAt !== null) return <StatusBadge>{t('replayViewer.share.revoked')}</StatusBadge>;
  if (!isShareActive(share, now)) return <StatusBadge>{t('replayViewer.share.expired')}</StatusBadge>;
  return null;
}

function ShareRow({ share, now, revoking, onRevoke }: { share: ReplayShare; now: number; revoking: boolean; onRevoke: (share: ReplayShare) => void }) {
  const { t } = useTranslation();
  const active = isShareActive(share, now);
  const details = [
    share.expiresAt === null ? t('replayViewer.share.neverExpires') : t('replayViewer.share.expires', { date: formatDateWithTime(new Date(share.expiresAt)) }),
    t('replayViewer.share.views', { count: share.viewCount }),
    share.lastViewedAt === null ? null : t('replayViewer.share.lastViewed', { date: formatDateWithTime(new Date(share.lastViewedAt)) }),
  ].filter((part) => part !== null);

  return (
    <li className="flex items-center gap-3 px-3 py-2.5">
      <div className={active ? 'min-w-0 flex-1 space-y-0.5' : 'min-w-0 flex-1 space-y-0.5 opacity-60'}>
        <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm">
          <span className="font-medium">{share.createdBy || t('common.unknown')}</span>
          <span className="text-muted-foreground">{formatDateWithTime(new Date(share.createdAt))}</span>
          <ShareStateBadge share={share} now={now} />
        </div>
        <p className="text-xs text-muted-foreground">{details.join(' · ')}</p>
      </div>
      {active && (
        <IconAction icon={Ban} label={t('replayViewer.share.revoke')} destructive disabled={revoking} onClick={() => { onRevoke(share); }} />
      )}
    </li>
  );
}

function ShareList({ replayId }: { replayId: string }) {
  const { t } = useTranslation();
  const { toast } = useToast();
  const shares = useReplayShares(replayId);
  const revoke = useRevokeReplayShare(replayId);
  const confirmation = useConfirmation();
  const [now] = useState(() => Date.now());

  const confirmRevoke = async (share: ReplayShare) => {
    const confirmed = await confirmation.confirm({
      title: t('replayViewer.share.revokeTitle'),
      description: t('replayViewer.share.revokeDescription'),
      action: t('replayViewer.share.revoke'),
      destructive: true,
    });
    if (!confirmed) return;
    revoke.mutate(share.id, {
      onError: () => {
        toast({ title: t('toast.error'), description: t('replayViewer.share.revokeFailed'), variant: 'destructive' });
      },
    });
  };

  if (shares.isPending) return <div className="flex justify-center py-4"><LoadingSpinner className="h-5 w-5 text-muted-foreground" /></div>;
  if (shares.isError) {
    return (
      <div className="flex items-center justify-between gap-3">
        <Notice variant="error" announce className="flex-1">{t('replayViewer.share.loadFailed')}</Notice>
        <Button type="button" variant="outline" size="sm" onClick={() => { void shares.refetch(); }}>{t('common.retry')}</Button>
      </div>
    );
  }
  if (shares.data.length === 0) return null;

  return (
    <>
      <ul className="max-h-72 divide-y overflow-y-auto rounded-md border">
        {shares.data.map((share) => (
          <ShareRow key={share.id} share={share} now={now} revoking={revoke.isPending} onRevoke={(target) => { void confirmRevoke(target); }} />
        ))}
      </ul>
      {confirmation.dialog}
    </>
  );
}

function ShareCreator({ replayId }: { replayId: string }) {
  const { t } = useTranslation();
  const { toast } = useToast();
  const [expiry, setExpiry] = useState<ShareExpiryChoice>(DEFAULT_SHARE_EXPIRY);
  const [created, setCreated] = useState<CreatedReplayShare | null>(null);
  const createShare = useCreateReplayShare(replayId);
  const shares = useReplayShares(replayId);
  const createdOpen = created !== null && !isShareRevoked(created.share.id, shares.data);

  const submit = () => {
    createShare.mutate(expiry, {
      onSuccess: setCreated,
      onError: (error) => {
        const key = CREATE_FAILURE_KEYS.get(apiErrorCode(error) ?? '') ?? 'replayViewer.share.createFailed';
        toast({ title: t('toast.error'), description: t(key), variant: 'destructive' });
      },
    });
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <Select value={expiry} onValueChange={(value) => { if (isExpiryChoice(value)) setExpiry(value); }}>
          <SelectTrigger aria-label={t('replayViewer.share.expiry.label')} className="w-44">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {SHARE_EXPIRY_CHOICES.map((choice) => <SelectItem key={choice} value={choice}>{t(EXPIRY_LABEL_KEYS[choice])}</SelectItem>)}
          </SelectContent>
        </Select>
        <Button type="button" disabled={createShare.isPending} onClick={submit}>
          {createShare.isPending && <Loader2 className="animate-spin" aria-hidden="true" />}
          {t('replayViewer.share.create')}
        </Button>
      </div>
      {createdOpen && <CreatedLink created={created} />}
    </div>
  );
}

interface ReplayShareDialogProps {
  replayId: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onCloseAutoFocus: (event: Event) => void;
}

export function ReplayShareDialog({ replayId, open, onOpenChange, onCloseAutoFocus }: ReplayShareDialogProps) {
  const { t } = useTranslation();
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent aria-describedby={undefined} className="max-w-lg" onCloseAutoFocus={onCloseAutoFocus}>
        <DialogHeader>
          <DialogTitle>{t('replayViewer.share.title')}</DialogTitle>
        </DialogHeader>
        <div className="min-w-0 space-y-5">
          <ShareCreator replayId={replayId} />
          <ShareList replayId={replayId} />
        </div>
      </DialogContent>
    </Dialog>
  );
}
