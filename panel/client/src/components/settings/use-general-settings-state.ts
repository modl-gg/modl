import { useTranslation } from 'react-i18next';
import { useGeneralSettings, usePatchGeneralSettings, type ServerIconKind } from '@/hooks/data/settings';
import { PERMISSIONS, usePermissions } from '@/hooks/use-permissions';
import { useServerIconUpload } from '@/hooks/use-server-icon-upload';
import { EMPTY_PANEL_IDENTITY, isPublicUrlValid, isServerNameValid, panelIdentityChanges, panelIdentityOf } from '@/lib/settings-identity';
import { panelUrlChangeRequest } from './PanelUrlField';
import { useConfirmation } from '@/hooks/use-confirmation';
import { useSettingsDraft } from './save/use-settings-draft';

export function usePanelIdentityDraft() {
  const { t } = useTranslation();
  const { hasPermission } = usePermissions();
  const { data: snapshot } = useGeneralSettings({ enabled: hasPermission(PERMISSIONS.ADMIN_SETTINGS_VIEW) });
  const patchGeneralSettings = usePatchGeneralSettings();
  const confirmation = useConfirmation();

  const draft = useSettingsDraft({
    snapshot,
    fallback: EMPTY_PANEL_IDENTITY,
    valueOf: panelIdentityOf,
    isValid: (identity) => isServerNameValid(identity, snapshot) && isPublicUrlValid(identity, snapshot),
    save: async (identity, base) => {
      const changes = panelIdentityChanges(identity, base);
      if (changes.publicUrl !== undefined) {
        if (!(await confirmation.confirm(panelUrlChangeRequest(t, changes.publicUrl)))) return null;
      }
      if (Object.keys(changes).length === 0) return base;
      return patchGeneralSettings.mutateAsync({ expectedVersion: base.version, changes });
    },
  });

  const iconUpload = useServerIconUpload((kind, url) => {
    draft.update((current) => (kind === 'homepage' ? { ...current, homepageIconUrl: url } : { ...current, panelIconUrl: url }));
  });

  const removeIcon = (kind: ServerIconKind) => {
    draft.update((current) => (kind === 'homepage' ? { ...current, homepageIconUrl: '' } : { ...current, panelIconUrl: '' }));
  };

  return { snapshot, draft, iconUpload, removeIcon, confirmationDialog: confirmation.dialog };
}
