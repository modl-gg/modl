import { useEffect, useRef, useState, type ReactElement } from 'react';
import { useTranslation } from 'react-i18next';
import { buttonVariants } from '@/components/ui/button';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';

export interface ConfirmationRequest {
  title: string;
  description: string;
  action: string;
  destructive?: boolean;
}

export interface Confirmation {
  confirm: (request: ConfirmationRequest) => Promise<boolean>;
  dialog: ReactElement;
}

export function useConfirmation(): Confirmation {
  const { t } = useTranslation();
  const [request, setRequest] = useState<ConfirmationRequest | null>(null);
  const [open, setOpen] = useState(false);
  const resolverRef = useRef<((confirmed: boolean) => void) | null>(null);

  useEffect(() => () => {
    resolverRef.current?.(false);
    resolverRef.current = null;
  }, []);

  const settle = (confirmed: boolean) => {
    resolverRef.current?.(confirmed);
    resolverRef.current = null;
    setOpen(false);
  };

  const confirm = async (next: ConfirmationRequest) => new Promise<boolean>((resolve) => {
    resolverRef.current?.(false);
    resolverRef.current = resolve;
    setRequest(next);
    setOpen(true);
  });

  const dialog = (
    <AlertDialog open={open} onOpenChange={(next) => { if (!next) settle(false); }}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{request?.title}</AlertDialogTitle>
          <AlertDialogDescription>{request?.description}</AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>{t('common.cancel')}</AlertDialogCancel>
          <AlertDialogAction
            className={request?.destructive === true ? buttonVariants({ variant: 'destructive' }) : undefined}
            onClick={() => { settle(true); }}
          >
            {request?.action}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );

  return { confirm, dialog };
}
