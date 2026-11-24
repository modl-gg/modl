import { useTranslation } from 'react-i18next';
import { useDiscardImport } from '@/hooks/data/hosted-import';
import { useConfirmation } from '@/hooks/use-confirmation';
import { useToast } from '@/hooks/use-toast';

export function useImportDiscard(importId: string) {
  const { t } = useTranslation();
  const { toast } = useToast();
  const discard = useDiscardImport();
  const confirmation = useConfirmation();

  const discardNow = () => {
    discard.mutate(importId, {
      onError: () => {
        toast({ title: t('toast.error'), description: t('pages.setup.import.discardFailed'), variant: 'destructive' });
      },
    });
  };

  const confirmDiscard = async (beforeDiscard?: () => void) => {
    const confirmed = await confirmation.confirm({
      title: t('pages.setup.import.review.discardTitle'),
      description: t('pages.setup.import.review.discardDesc'),
      action: t('pages.setup.import.review.discard'),
      destructive: true,
    });
    if (!confirmed) return;
    beforeDiscard?.();
    discardNow();
  };

  return { pending: discard.isPending, discardNow, confirmDiscard, dialog: confirmation.dialog };
}
