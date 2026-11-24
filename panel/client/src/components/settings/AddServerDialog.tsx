import { Plus } from 'lucide-react';
import { useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';

import { Button } from '@/components/ui/button';
import { SettingsPageActionButton } from './layout/SettingsPageFrame';
import { Checkbox } from '@/components/ui/checkbox';
import { CopyButton } from '@/components/ui/copy-button';
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Notice } from '@/components/ui/notice';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import { useCreateServerKey, type IssuedServerKey, type ServerKeyRole } from '@/hooks/data/server-keys';
import { apiErrorCode } from '@/lib/http-error';
import {
  isPlainHttpToPublicHost,
  planConnectionUrl,
  renderConnectionSnippet,
  type ConnectionMode,
} from '@/lib/connection-snippet';

const ROLE_OPTIONS: readonly { role: ServerKeyRole; labelKey: string }[] = [
  { role: 'core', labelKey: 'settings.servers.roleCore' },
  { role: 'behind-proxy', labelKey: 'settings.servers.roleBehindProxy' },
];
const MODE_BY_ROLE: Record<ServerKeyRole, ConnectionMode> = { 'behind-proxy': 'behind-proxy', core: 'remote' };
const CREATE_ERROR_KEYS: ReadonlyMap<string, string> = new Map([
  ['SERVER_KEY_LIMIT', 'settings.servers.limitReached'],
  ['SERVER_KEY_NAME_TAKEN', 'settings.servers.nameTaken'],
]);
const NAME_PATTERN = /^[A-Za-z0-9_.-]{1,64}$/;
const CONNECTION_FILE_NAME = 'connection.yml';
const DOWNLOAD_RELEASE_MS = 1_000;

function downloadConnectionFile(content: string) {
  const url = URL.createObjectURL(new Blob([`${content}\n`], { type: 'application/yaml' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = CONNECTION_FILE_NAME;
  link.click();
  window.setTimeout(() => { URL.revokeObjectURL(url); }, DOWNLOAD_RELEASE_MS);
}

function NewServerForm({ onIssued }: { onIssued: (issued: IssuedServerKey) => void }) {
  const { t } = useTranslation();
  const createKey = useCreateServerKey();
  const [name, setName] = useState('');
  const [role, setRole] = useState<ServerKeyRole>('core');
  const [allowProxyCommands, setAllowProxyCommands] = useState(false);

  const selectRole = (selected: string) => {
    const option = ROLE_OPTIONS.find((candidate) => candidate.role === selected);
    if (option) setRole(option.role);
  };

  const submit = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    createKey.mutate({ name, role, allowProxyCommands }, { onSuccess: onIssued });
  };

  const errorKey = CREATE_ERROR_KEYS.get(apiErrorCode(createKey.error) ?? '') ?? 'settings.servers.createFailed';

  return (
    <form className="space-y-4" onSubmit={submit}>
      <div className="space-y-2">
        <Label htmlFor="server-key-name">{t('settings.servers.nameLabel')}</Label>
        <Input
          id="server-key-name"
          value={name}
          autoComplete="off"
          maxLength={64}
          aria-describedby={role === 'behind-proxy' ? 'server-key-name-hint' : undefined}
          onChange={(event) => { setName(event.target.value); }}
        />
        {role === 'behind-proxy' && (
          <p id="server-key-name-hint" className="text-xs text-muted-foreground">{t('settings.servers.nameHint')}</p>
        )}
      </div>
      <fieldset className="space-y-2">
        <legend className="text-sm font-medium">{t('settings.servers.roleLabel')}</legend>
        <RadioGroup value={role} onValueChange={selectRole}>
          {ROLE_OPTIONS.map((option) => (
            <div key={option.role} className="flex items-center gap-2">
              <RadioGroupItem id={`server-key-role-${option.role}`} value={option.role} />
              <Label htmlFor={`server-key-role-${option.role}`}>{t(option.labelKey)}</Label>
            </div>
          ))}
        </RadioGroup>
      </fieldset>
      {role === 'behind-proxy' && (
        <div className="flex items-start gap-3">
          <Checkbox
            className="mt-0.5"
            id="server-key-proxy-commands"
            checked={allowProxyCommands}
            aria-describedby="server-key-proxy-commands-hint"
            onCheckedChange={(checked) => { setAllowProxyCommands(checked === true); }}
          />
          <div className="space-y-1">
            <Label htmlFor="server-key-proxy-commands">{t('settings.servers.allowProxyCommands')}</Label>
            <p id="server-key-proxy-commands-hint" className="text-xs text-muted-foreground">
              {t('settings.servers.allowProxyCommandsHint')}
            </p>
          </div>
        </div>
      )}
      {createKey.isError && <Notice variant="error" announce>{t(errorKey)}</Notice>}
      <DialogFooter>
        <Button type="submit" disabled={!NAME_PATTERN.test(name) || createKey.isPending}>
          {t('settings.servers.create')}
        </Button>
      </DialogFooter>
    </form>
  );
}

function IssuedKeyConfiguration({ issued }: { issued: IssuedServerKey }) {
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

const AddServerDialog = () => {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const [issued, setIssued] = useState<IssuedServerKey | null>(null);

  const changeOpen = (next: boolean) => {
    setOpen(next);
    if (!next) setIssued(null);
  };

  return (
    <Dialog open={open} onOpenChange={changeOpen}>
      <DialogTrigger asChild>
        <SettingsPageActionButton icon={Plus} label={t('settings.servers.addServer')} />
      </DialogTrigger>
      <DialogContent
        aria-describedby={undefined}
        onInteractOutside={(event) => {
          if (issued) event.preventDefault();
        }}
      >
        <DialogHeader>
          <DialogTitle>{issued?.name ?? t('settings.servers.addServerTitle')}</DialogTitle>
        </DialogHeader>
        {issued ? <IssuedKeyConfiguration issued={issued} /> : <NewServerForm onIssued={setIssued} />}
      </DialogContent>
    </Dialog>
  );
};

export default AddServerDialog;
