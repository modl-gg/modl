import { useTranslation } from 'react-i18next';

import { Button } from '@/components/ui/button';
import { CopyButton } from '@/components/ui/copy-button';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Notice } from '@/components/ui/notice';
import type { IssuedServerKey, ServerKeyRole } from '@/hooks/data/server-keys';
import { apiErrorCode } from '@/lib/http-error';
import {
  isPlainHttpToPublicHost,
  planConnectionUrl,
  renderConnectionSnippet,
  type ConnectionMode,
} from '@/lib/connection-snippet';

const MODE_BY_ROLE: Record<ServerKeyRole, ConnectionMode> = { 'behind-proxy': 'behind-proxy', core: 'remote' };
const CREATE_ERROR_KEYS: ReadonlyMap<string, string> = new Map([
  ['SERVER_KEY_LIMIT', 'settings.servers.limitReached'],
  ['SERVER_KEY_NAME_TAKEN', 'settings.servers.nameTaken'],
]);
const CONNECTION_FILE_NAME = 'connection.yml';
const DOWNLOAD_RELEASE_MS = 1_000;

export function createKeyErrorKey(error: unknown): string {
  return CREATE_ERROR_KEYS.get(apiErrorCode(error) ?? '') ?? 'settings.servers.createFailed';
}

function downloadConnectionFile(content: string) {
  const url = URL.createObjectURL(new Blob([`${content}\n`], { type: 'application/yaml' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = CONNECTION_FILE_NAME;
  link.click();
  window.setTimeout(() => { URL.revokeObjectURL(url); }, DOWNLOAD_RELEASE_MS);
}

export function IssuedKeyConfiguration({ issued }: { issued: IssuedServerKey }) {
  const { t } = useTranslation();
  const { primary, sameMachine, loopbackOnly } = planConnectionUrl(issued.panelUrl, window.location.origin);
  const snippet = renderConnectionSnippet({ mode: MODE_BY_ROLE[issued.role], url: primary, apiKey: issued.secret });

  return (
    <div className="min-w-0 space-y-3">
      <Notice variant="info" announce>{t('settings.servers.secretShownOnce')}</Notice>
      <div className="flex items-start gap-2">
        <pre className="min-w-0 flex-1 overflow-x-auto rounded-md border bg-surface-2 p-3 text-xs font-mono" tabIndex={0}>
          <code>{snippet}</code>
        </pre>
        <CopyButton
          value={snippet}
          label={t('settings.servers.copySnippet')}
          failureMessage={t('settings.servers.copySnippetFailed')}
        />
      </div>
      <Button type="button" variant="outline" size="sm" onClick={() => { downloadConnectionFile(snippet); }}>
        {t('settings.servers.downloadConnectionYml')}
      </Button>
      <p className="text-xs text-muted-foreground">{t('settings.servers.installHint')}</p>
      {sameMachine && (
        <p className="text-xs text-muted-foreground">{t('settings.servers.sameMachineHint', { url: sameMachine })}</p>
      )}
      {loopbackOnly && <Notice variant="warning">{t('settings.servers.loopbackWarning')}</Notice>}
      {isPlainHttpToPublicHost(primary) && <Notice variant="warning">{t('settings.servers.plainHttpWarning')}</Notice>}
    </div>
  );
}

interface IssuedServerKeyDialogProps {
  issued: IssuedServerKey | null;
  open: boolean;
  onClose: () => void;
  onClosed: () => void;
}

export function IssuedServerKeyDialog({ issued, open, onClose, onClosed }: IssuedServerKeyDialogProps) {
  return (
    <Dialog open={open && issued !== null} onOpenChange={(next) => { if (!next) onClose(); }}>
      <DialogContent aria-describedby={undefined} onInteractOutside={(event) => { event.preventDefault(); }} onCloseAutoFocus={onClosed}>
        <DialogHeader>
          <DialogTitle>{issued?.name}</DialogTitle>
        </DialogHeader>
        {issued && <IssuedKeyConfiguration issued={issued} />}
      </DialogContent>
    </Dialog>
  );
}
