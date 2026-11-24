import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Label } from '@/components/ui/label';
import { Notice } from '@/components/ui/notice';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { useToast } from '@/hooks/use-toast';
import { useConnectedServers } from '@/hooks/data/servers';
import { useInstallUpdate, type AvailableUpdate, type UpdateStatus } from '@/hooks/data/updates';
import { updateErrorKey } from './update-errors';

interface UpdateNowDialogProps {
  status: UpdateStatus;
  release: AvailableUpdate;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

function lineOf(version: string): string {
  return version.split('.').slice(0, 2).join('.');
}

function ServersToUpdate({ line }: { line: string }) {
  const servers = useConnectedServers();
  const behind = (servers.data ?? []).filter((server) => lineOf(server.pluginVersion) !== line);
  if (behind.length === 0) return null;
  return (
    <ul className="max-h-40 overflow-y-auto rounded border text-sm">
      {behind.map((server) => (
        <li key={server.instanceId} className="flex justify-between gap-2 border-b px-3 py-1 last:border-b-0">
          <span>{server.name || server.platform}</span>
          <span className="font-mono text-xs text-muted-foreground">{server.pluginVersion}</span>
        </li>
      ))}
    </ul>
  );
}

export function UpdateNowDialog({ status, release, open, onOpenChange }: UpdateNowDialogProps) {
  const { t } = useTranslation();
  const { toast } = useToast();
  const install = useInstallUpdate();
  const [acknowledged, setAcknowledged] = useState(false);
  const needsAcknowledgement = release.requiresPluginUpdate;
  const line = lineOf(release.version);

  const confirm = () => {
    install.mutate(
      { version: release.version, acknowledgePluginUpdate: acknowledged },
      {
        onSuccess: () => {
          onOpenChange(false);
        },
        onError: (error) => {
          toast({ title: t(updateErrorKey(error)), variant: 'destructive' });
        },
      },
    );
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t('updates.dialog.title', { version: release.version })}</DialogTitle>
          <DialogDescription>{t('updates.dialog.downtime')}</DialogDescription>
        </DialogHeader>
        {status.preUpdateBackup ? (
          <p className="text-sm">{t('updates.dialog.backup', { version: status.currentVersion })}</p>
        ) : (
          <Notice variant="warning">{t('updates.dialog.backupDisabled')}</Notice>
        )}
        {needsAcknowledgement && (
          <div className="space-y-3">
            <Notice variant="warning" title={t('updates.dialog.pluginTitle')}>
              {t('updates.dialog.pluginWarning', { line })}
            </Notice>
            <ServersToUpdate line={line} />
            <div className="flex items-center gap-2">
              <Checkbox
                id="update-plugin-acknowledgement"
                checked={acknowledged}
                onCheckedChange={(checked) => {
                  setAcknowledged(checked === true);
                }}
              />
              <Label htmlFor="update-plugin-acknowledgement">{t('updates.dialog.acknowledge')}</Label>
            </div>
          </div>
        )}
        <DialogFooter>
          <Button
            variant="outline"
            onClick={() => {
              onOpenChange(false);
            }}
          >
            {t('common.cancel')}
          </Button>
          <Button onClick={confirm} disabled={install.isPending || (needsAcknowledgement && !acknowledged)}>
            {install.isPending && <Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden="true" />}
            {t('updates.dialog.confirm')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
