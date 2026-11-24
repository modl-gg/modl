import { useTranslation } from 'react-i18next';
import { useToast } from '@/hooks/use-toast';
import { panelUrlSaveFailure, usePatchGeneralSettings, type PanelUrlSaveOutcome } from '@/hooks/data/settings';

export function usePanelUrlSave() {
  const { t } = useTranslation();
  const { toast } = useToast();
  const patchSettings = usePatchGeneralSettings();

  return async (origin: string, expectedVersion: number): Promise<PanelUrlSaveOutcome> => {
    try {
      await patchSettings.mutateAsync({ expectedVersion, changes: { publicUrl: origin } });
      return 'saved';
    } catch (error) {
      const outcome = panelUrlSaveFailure(error);
      if (outcome === 'conflict') {
        toast({ title: t('settings.page.updateConflict'), description: t('settings.general.panelUrlConflict'), variant: 'destructive' });
      } else if (outcome === 'failed') {
        toast({ title: t('toast.error'), description: t('settings.general.panelUrlSaveFailed'), variant: 'destructive' });
      }
      return outcome;
    }
  };
}
