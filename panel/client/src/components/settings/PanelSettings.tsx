import { useTranslation } from 'react-i18next';
import { PanelUrlInput } from './PanelUrlField';
import { DefaultLanguageField, ServerIconsField, ServerNameField } from './ServerIdentityFields';
import { SettingsLoading, SettingsSection, SettingsSections } from './layout/SettingsSections';
import { isPublicUrlValid, isServerNameValid } from '@/lib/settings-identity';
import { usePanelIdentityDraft } from './use-general-settings-state';

export default function PanelSettings() {
  const { t } = useTranslation();
  const { snapshot, draft, iconUpload, removeIcon, confirmationDialog } = usePanelIdentityDraft();
  const identity = draft.value;

  if (snapshot === undefined) return <SettingsLoading />;

  return (
    <SettingsSections>
      <SettingsSection>
        <ServerNameField
          value={identity.serverDisplayName}
          onChange={(serverDisplayName) => { draft.update((current) => ({ ...current, serverDisplayName })); }}
          managed={snapshot.serverNameManaged}
          error={isServerNameValid(identity, snapshot) ? undefined : t('settings.general.serverNameRequired')}
        />
        <PanelUrlInput
          value={identity.publicUrl}
          onChange={(publicUrl) => { draft.update((current) => ({ ...current, publicUrl })); }}
          managedByConfig={snapshot.publicUrlManagedByConfig}
          invalid={!isPublicUrlValid(identity, snapshot)}
        />
        <DefaultLanguageField
          value={identity.defaultLanguage}
          onChange={(defaultLanguage) => { draft.update((current) => ({ ...current, defaultLanguage })); }}
        />
      </SettingsSection>
      <SettingsSection>
        <div className="max-w-xl">
          <ServerIconsField
            iconUrls={{ homepage: identity.homepageIconUrl, panel: identity.panelIconUrl }}
            isUploading={iconUpload.isUploading}
            onUpload={(kind, file) => { void iconUpload.upload(kind, file); }}
            onRemove={removeIcon}
          />
        </div>
      </SettingsSection>
      {confirmationDialog}
    </SettingsSections>
  );
}
