import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Eye, EyeOff, Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { CopyIconButton } from '@/components/ui/copy-button';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '@/components/ui/alert-dialog';
import { useToast } from '@/hooks/use-toast';
import { Notice } from '@/components/ui/notice';
import { LoadingSpinner } from '@/components/ui/loading-spinner';
import { usePermissions } from '@/hooks/use-permissions';
import { useApiKeySecret, useApiKeyStatus, useGenerateApiKey } from '@/hooks/data/settings';
import { useCopyToClipboard } from '@/hooks/use-copy-to-clipboard';
import { WIKI_API_URL } from '@/lib/project-links';
import { SettingsSection } from './layout/SettingsSections';

const MASKED_API_KEY = '••••••••••••••••••••••••••••••••';

function presentApiKey(secret: string | null, visible: boolean): string {
  if (!secret) return MASKED_API_KEY;
  if (visible) return secret;
  return `${secret.substring(0, 8)}••••••••••••••••••••••••${secret.substring(secret.length - 4)}`;
}

const ApiKeySettings = () => {
  const { t } = useTranslation();
  const { toast } = useToast();
  const { isSuperAdmin } = usePermissions();
  const { data: status, isLoading, isError, refetch } = useApiKeyStatus(isSuperAdmin);
  const { secret, isFetching: isFetchingSecret, fetchSecret } = useApiKeySecret();
  const generateApiKey = useGenerateApiKey();
  const { copy, copiedValue } = useCopyToClipboard(t('settings.general.copyApiKeyFailed'));
  const [isKeyVisible, setIsKeyVisible] = useState(false);
  const [wasRotated, setWasRotated] = useState(false);

  const managedByConfig = status?.managedByConfig === true;

  const loadSecret = async (): Promise<string | null> => {
    try {
      return await fetchSecret();
    } catch {
      toast({ title: t('toast.error'), description: t('settings.page.revealApiKeyFailed'), variant: 'destructive' });
      return null;
    }
  };

  const toggleVisibility = async () => {
    if (isKeyVisible) {
      setIsKeyVisible(false);
      return;
    }
    if (await loadSecret()) {
      setIsKeyVisible(true);
    }
  };

  const prefetchSecret = () => {
    void fetchSecret().catch(() => undefined);
  };

  const copyKey = async (origin: Element) => {
    const key = await loadSecret();
    if (key) await copy(key, origin);
  };

  const regenerate = () => {
    const hadKey = status?.exists === true;
    generateApiKey.mutate(undefined, {
      onSuccess: () => {
        setIsKeyVisible(true);
        setWasRotated(hadKey);
      },
      onError: () => {
        toast({ title: t('toast.error'), description: t('settings.page.generateApiKeyFailed'), variant: 'destructive' });
      },
    });
  };

  const copied = secret !== null && copiedValue === secret;

  const renderKey = () => (
    <>
      <div className="flex max-w-xl items-center gap-2">
        <code className="min-w-0 flex-1 break-all rounded-md border bg-surface-2 px-3 py-2 font-mono text-sm">{presentApiKey(secret, isKeyVisible)}</code>
        <Button
          variant="outline"
          size="icon"
          onClick={() => { void toggleVisibility(); }}
          disabled={isFetchingSecret}
          aria-label={isKeyVisible ? t('settings.general.hideApiKey') : t('settings.general.showApiKey')}
          title={isKeyVisible ? t('settings.general.hideApiKey') : t('settings.general.showApiKey')}
        >
          {isKeyVisible ? <EyeOff className="h-4 w-4" aria-hidden="true" /> : <Eye className="h-4 w-4" aria-hidden="true" />}
        </Button>
        <CopyIconButton
          copied={copied}
          label={t('settings.general.copyApiKey')}
          size="icon"
          disabled={isFetchingSecret}
          onCopy={(origin) => { void copyKey(origin); }}
          onPointerEnter={prefetchSecret}
          onFocus={prefetchSecret}
        />
      </div>

      {wasRotated && (
        <Notice variant="warning" title={t('settings.general.apiKeyRotatedTitle')} announce>
          {t('settings.general.apiKeyRotatedDesc')}
        </Notice>
      )}

      {managedByConfig ? (
        <p className="text-xs text-muted-foreground">{t('settings.general.apiKeyManagedDesc')}</p>
      ) : (
        <AlertDialog>
          <AlertDialogTrigger asChild>
            <Button variant="outline" disabled={generateApiKey.isPending}>
              {generateApiKey.isPending && <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />}
              {t('settings.general.regenerate')}
            </Button>
          </AlertDialogTrigger>
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>{t('settings.general.regenerateApiKeyTitle')}</AlertDialogTitle>
              <AlertDialogDescription>{t('settings.general.regenerateApiKeyConfirm')}</AlertDialogDescription>
            </AlertDialogHeader>
            <AlertDialogFooter>
              <AlertDialogCancel>{t('common.cancel')}</AlertDialogCancel>
              <AlertDialogAction onClick={regenerate} className="bg-destructive text-destructive-foreground hover:bg-destructive/90">
                {t('settings.general.regenerate')}
              </AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      )}
    </>
  );

  const renderBody = () => {
    if (isLoading) {
      return (
        <div className="flex justify-center py-6">
          <LoadingSpinner className="h-6 w-6 text-muted-foreground" />
        </div>
      );
    }

    if (isError || !status) {
      return (
        <>
          <Notice variant="error" announce>{t('settings.general.apiKeyLoadFailed')}</Notice>
          <Button type="button" variant="outline" size="sm" onClick={() => { void refetch(); }}>
            {t('common.retry')}
          </Button>
        </>
      );
    }

    if (!status.exists) {
      return (
        <div>
          <Button variant="outline" onClick={regenerate} disabled={generateApiKey.isPending || managedByConfig}>
            {generateApiKey.isPending && <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />}
            {t('settings.general.generateApiKey')}
          </Button>
        </div>
      );
    }

    return renderKey();
  };

  return (
    <SettingsSection
      title={t('settings.page.apiKey')}
      action={(
        <a href={WIKI_API_URL} target="_blank" rel="noopener noreferrer" className="text-xs text-muted-foreground hover:text-foreground hover:underline">
          {t('settings.general.apiDocsLink')}
        </a>
      )}
    >
      {renderBody()}
    </SettingsSection>
  );
};

export default ApiKeySettings;
