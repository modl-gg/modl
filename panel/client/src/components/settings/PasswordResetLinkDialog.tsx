import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { CopyButton } from '@/components/ui/copy-button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import type { PasswordResetLink } from '@/hooks/data/staff-security';

export function PasswordResetLinkDialog({ link, onClose }: { link: PasswordResetLink | null; onClose: () => void }) {
  const { t } = useTranslation();

  return (
    <Dialog open={link !== null} onOpenChange={(open) => { if (!open) onClose(); }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t('settings.staff.security.linkTitle')}</DialogTitle>
          <DialogDescription>{t('settings.staff.security.linkDesc', { email: link?.email ?? '' })}</DialogDescription>
        </DialogHeader>
        {link !== null && (
          <div className="flex items-center gap-2">
            <code className="flex-1 break-all rounded border bg-surface-2 px-3 py-2 font-mono text-xs">{link.url}</code>
            <CopyButton
              value={link.url}
              label={t('settings.staff.security.copy')}
              failureMessage={t('settings.staff.security.copyFailed')}
            />
          </div>
        )}
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose}>{t('common.close')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
