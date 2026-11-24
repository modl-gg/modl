import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Loader2 } from 'lucide-react';
import { buttonVariants } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from '@/components/ui/alert-dialog';
import { useRollbackAuditPunishment } from '@/hooks/data/audit';
import { useToast } from '@/hooks/use-toast';
import { describeError } from '@/lib/http-error';
import { MAX_REASON_LENGTH } from './audit-format';

export interface RollbackTarget {
  punishmentId: string;
  summary: string;
}

export function RollbackPunishmentDialog({ target, onClose }: { target: RollbackTarget | null; onClose: () => void }) {
  const { t } = useTranslation();
  const { toast } = useToast();
  const rollback = useRollbackAuditPunishment();
  const reasonId = useId();
  const [reason, setReason] = useState('');
  const trimmedReason = reason.trim();

  const close = () => {
    setReason('');
    onClose();
  };

  const confirm = () => {
    if (target === null || trimmedReason === '') return;
    rollback.mutate({ punishmentId: target.punishmentId, reason: trimmedReason }, {
      onSuccess: () => {
        toast({ title: t('audit.punishmentRolledBack') });
        close();
      },
      onError: (error) => {
        toast({
          title: t('audit.rollbackFailed'),
          description: describeError(error, t('audit.unknownError')),
          variant: 'destructive',
        });
      },
    });
  };

  return (
    <AlertDialog
      open={target !== null}
      onOpenChange={(open) => {
        if (!open) close();
      }}
    >
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{t('audit.rollbackThisPunishment')}</AlertDialogTitle>
          <AlertDialogDescription>{target?.summary}</AlertDialogDescription>
        </AlertDialogHeader>
        <div className="space-y-2">
          <Label htmlFor={reasonId}>{t('common.reason')}</Label>
          <Textarea
            id={reasonId}
            value={reason}
            onChange={(event) => { setReason(event.target.value); }}
            className="min-h-[80px]"
            maxLength={MAX_REASON_LENGTH}
          />
        </div>
        <AlertDialogFooter>
          <AlertDialogCancel disabled={rollback.isPending}>{t('common.cancel')}</AlertDialogCancel>
          <AlertDialogAction
            onClick={(event) => {
              event.preventDefault();
              confirm();
            }}
            disabled={trimmedReason === '' || rollback.isPending}
            className={buttonVariants({ variant: 'destructive' })}
          >
            {rollback.isPending && <Loader2 className="h-4 w-4 animate-spin" />}
            {t('common.confirm')}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
