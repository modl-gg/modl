import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useConfirmation } from '@/hooks/use-confirmation';
import { useToast } from '@/hooks/use-toast';
import { useAuth } from '@/hooks/use-auth';
import { useContinueGateActive } from '@/hooks/use-before-continue';
import { useCreateMinecraftLinkCode, useUnlinkMinecraftAccount, type MinecraftLinkCode } from '@/hooks/data/account';
import { useMinecraftLinkPolling } from '@/hooks/data/signed-in-profile';
import { formatCountdown, useSecondsRemaining } from '@/hooks/use-seconds-remaining';
import { getAvatarUrl } from '@/lib/api';
import { FIELD_WIDTH } from './layout/SettingsSections';
import { CopyButton } from '@/components/ui/copy-button';

function PendingLinkCode({ linkCode, secondsLeft, onCancel }: { linkCode: MinecraftLinkCode; secondsLeft: number; onCancel: () => void }) {
  const { t } = useTranslation();

  return (
    <div className="space-y-3">
      <p className="text-sm text-muted-foreground">{t('settings.minecraftAccount.runCommand')}</p>
      <div className="flex items-center gap-2">
        <code className="flex-1 text-sm font-mono bg-surface-2 px-3 py-2 rounded border break-all">{linkCode.command}</code>
        <CopyButton
          value={linkCode.command}
          label={t('settings.minecraftAccount.copyCommand')}
          failureMessage={t('settings.minecraftAccount.copyFailed')}
        />
      </div>
      <div className="flex items-center justify-between gap-2">
        <span className="flex items-center gap-2 text-xs text-muted-foreground">
          <Loader2 className="h-3 w-3 animate-spin" aria-hidden="true" />
          {t('settings.minecraftAccount.waiting', { time: formatCountdown(secondsLeft) })}
        </span>
        <Button variant="ghost" size="sm" onClick={onCancel}>{t('common.cancel')}</Button>
      </div>
    </div>
  );
}

const MinecraftAccountSettings = () => {
  const { t } = useTranslation();
  const { toast } = useToast();
  const { user } = useAuth();
  const minecraftAccount = user?.minecraftAccount ?? null;
  const createLinkCode = useCreateMinecraftLinkCode();
  const unlinkAccount = useUnlinkMinecraftAccount();
  const confirmation = useConfirmation();
  const insideSetup = useContinueGateActive();
  const [linkCode, setLinkCode] = useState<MinecraftLinkCode | null>(null);
  const pendingLinkCode = minecraftAccount === null ? linkCode : null;
  const secondsLeft = useSecondsRemaining(pendingLinkCode?.expiresAt ?? null);
  const activeLinkCode = pendingLinkCode !== null && secondsLeft > 0 ? pendingLinkCode : null;
  const codeExpired = pendingLinkCode !== null && secondsLeft === 0;
  useMinecraftLinkPolling(activeLinkCode !== null);

  const showUnlinkFailed = () => {
    toast({ title: t('toast.error'), description: t('settings.minecraftAccount.unlinkFailed'), variant: 'destructive' });
  };

  const startLinking = () => {
    createLinkCode.mutate(undefined, {
      onSuccess: setLinkCode,
      onError: () => {
        toast({ title: t('toast.error'), description: t('settings.minecraftAccount.createCodeFailed'), variant: 'destructive' });
      },
    });
  };

  const unlink = async () => {
    const confirmed = await confirmation.confirm({
      title: t('settings.minecraftAccount.unlinkTitle'),
      description: t('settings.minecraftAccount.unlinkDesc'),
      action: t('settings.minecraftAccount.unlink'),
      destructive: true,
    });
    if (!confirmed) return;
    unlinkAccount.mutate(undefined, {
      onSuccess: () => {
        setLinkCode(null);
      },
      onError: showUnlinkFailed,
    });
  };

  const renderBody = () => {
    if (minecraftAccount) {
      return (
        <div className={`flex items-center gap-3 rounded-md border px-3 py-2 ${FIELD_WIDTH.text}`}>
          <img src={getAvatarUrl(minecraftAccount.uuid, 32)} alt="" className="h-8 w-8 rounded" />
          <div className="flex-1 min-w-0">
            <p className="text-sm font-medium">{minecraftAccount.username || t('common.unknown')}</p>
            <p className="text-xs text-muted-foreground font-mono truncate">{minecraftAccount.uuid}</p>
          </div>
          <Button variant="outline" size="sm" disabled={unlinkAccount.isPending} onClick={() => { void unlink(); }}>
            {t('settings.minecraftAccount.unlink')}
          </Button>
        </div>
      );
    }

    if (activeLinkCode !== null) {
      return <PendingLinkCode linkCode={activeLinkCode} secondsLeft={secondsLeft} onCancel={() => setLinkCode(null)} />;
    }

    return (
      <div className="space-y-3">
        {codeExpired
          ? <p className="text-sm text-muted-foreground">{t('settings.minecraftAccount.codeExpired')}</p>
          : !insideSetup && <p className="text-sm text-muted-foreground">{t('settings.minecraftAccount.description')}</p>}
        <Button variant="outline" size="sm" onClick={startLinking} disabled={createLinkCode.isPending}>
          {createLinkCode.isPending && <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />}
          {t('settings.minecraftAccount.link')}
        </Button>
      </div>
    );
  };

  return (
    <>
      {renderBody()}
      {confirmation.dialog}
    </>
  );
};

export default MinecraftAccountSettings;
