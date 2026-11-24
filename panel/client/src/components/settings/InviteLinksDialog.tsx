import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { CopyField } from '@/components/ui/copy-button';
import type { InviteLink } from '@/hooks/data/staff';

interface InviteLinksDialogProps {
  links: InviteLink[];
  onClose: () => void;
}

export function InviteLinksDialog({ links, onClose }: InviteLinksDialogProps) {
  const { t } = useTranslation();

  return (
    <Dialog open={links.length > 0} onOpenChange={(open) => { if (!open) onClose(); }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t('settings.staff.inviteLinksTitle')}</DialogTitle>
          <DialogDescription>{t('settings.staff.inviteLinksDesc')}</DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          {links.map((link, index) => (
            <CopyField
              key={link.email}
              id={`invite-link-${index}`}
              fieldLabel={link.email}
              value={link.url}
              label={t('settings.staff.copyInviteLink')}
              failureMessage={t('settings.staff.copyInviteLinkFailed')}
            />
          ))}
        </div>
        <DialogFooter>
          <Button type="button" onClick={onClose}>{t('common.close')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
