import { useTranslation } from 'react-i18next';
import { useToast } from '@/hooks/use-toast';
import { apiErrorCode } from '@/lib/http-error';

const KEY_INVALID_CODE = 'IP_LOOKUP_KEY_INVALID';

export function usePlayerAddressFailureMessage() {
  const { t } = useTranslation();
  return (error: unknown): string => (apiErrorCode(error) === KEY_INVALID_CODE
    ? t('settings.playerAddresses.keyInvalid')
    : t('settings.playerAddresses.saveFailed'));
}

export function usePlayerAddressFailureToast() {
  const { t } = useTranslation();
  const { toast } = useToast();
  const describe = usePlayerAddressFailureMessage();
  return (error: unknown) => {
    toast({ title: t('toast.error'), description: describe(error), variant: 'destructive' });
  };
}
