import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useToast } from '@/hooks/use-toast';
import { copyText } from '@/lib/copy-text';

const COPIED_FEEDBACK_MS = 2000;

export interface CopyToClipboard {
  copy: (value: string, origin?: Element | null) => Promise<boolean>;
  copiedValue: string | null;
}

export function useCopyToClipboard(failureMessage: string): CopyToClipboard {
  const { t } = useTranslation();
  const { toast } = useToast();
  const [copiedValue, setCopiedValue] = useState<string | null>(null);
  const resetTimerRef = useRef<number | undefined>(undefined);

  useEffect(() => () => { window.clearTimeout(resetTimerRef.current); }, []);

  const copy = useCallback(async (value: string, origin: Element | null = null): Promise<boolean> => {
    if (!(await copyText(value, origin))) {
      toast({ title: t('toast.error'), description: failureMessage, variant: 'destructive' });
      return false;
    }
    setCopiedValue(value);
    window.clearTimeout(resetTimerRef.current);
    resetTimerRef.current = window.setTimeout(() => { setCopiedValue(null); }, COPIED_FEEDBACK_MS);
    return true;
  }, [failureMessage, t, toast]);

  return { copy, copiedValue };
}
