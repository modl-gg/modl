import { useCallback, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { toast } from '@/hooks/use-toast';
import {
  ticketValidationProblem,
  type TicketFormData,
  type TicketFormValidation,
  type TicketPendingFiles,
} from '@/lib/ticket-form';

export function useTicketFormDraft() {
  const [formData, setFormData] = useState<TicketFormData>({});
  const [pendingFiles, setPendingFiles] = useState<TicketPendingFiles>({});

  const setField = useCallback((fieldId: string, value: string) => {
    setFormData((prev) => ({ ...prev, [fieldId]: value }));
  }, []);

  const selectFile = useCallback((fieldId: string, file: File) => {
    setFormData((prev) => ({ ...prev, [fieldId]: file.name }));
    setPendingFiles((prev) => ({ ...prev, [fieldId]: [file] }));
  }, []);

  return { formData, pendingFiles, setField, selectFile };
}

export function useTicketFormNotices() {
  const { t } = useTranslation();

  const reportValidationProblem = (validation: TicketFormValidation): boolean => {
    const problem = ticketValidationProblem(validation);
    if (problem === null) return false;
    toast({ title: t(problem.titleKey), description: t(problem.descriptionKey, problem.values ?? {}), variant: 'destructive' });
    return true;
  };

  const reportHiddenFiles = (count: number) => {
    if (count === 0) return;
    toast({
      title: t('submitTicket.hiddenAttachmentsExcluded'),
      description: t('submitTicket.hiddenAttachmentsExcludedDesc', { count }),
    });
  };

  return { reportValidationProblem, reportHiddenFiles };
}
