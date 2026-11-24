import { useTranslation } from 'react-i18next';
import { useToast } from '@/hooks/use-toast';
import { ApiHttpError, describeError, isConflictError } from '@/lib/http-error';

const HTTP_FORBIDDEN = 403;

export type SaveRejectionDescriber = (error: unknown) => string | null;

export type SaveFailureReporter = (error: unknown, describeRejection?: SaveRejectionDescriber) => void;

export function useSaveFailureReporter(): SaveFailureReporter {
  const { t } = useTranslation();
  const { toast } = useToast();

  return (error, describeRejection) => {
    const rejection = describeRejection?.(error) ?? null;
    if (rejection !== null) {
      toast({ title: t('toast.saveFailed'), description: rejection, variant: 'destructive' });
      return;
    }
    if (isConflictError(error)) {
      toast({ title: t('toast.saveFailed'), description: t('settings.saveBar.conflict'), variant: 'destructive' });
      return;
    }
    if (error instanceof ApiHttpError && error.status === HTTP_FORBIDDEN) {
      toast({
        title: t('toast.permissionDenied'),
        description: describeError(error, t('settings.page.modifyPermissionDenied')),
        variant: 'destructive',
      });
      return;
    }
    toast({ title: t('toast.saveFailed'), description: describeError(error, t('common.unknownError')), variant: 'destructive' });
  };
}
