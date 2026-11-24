import AddServerDialog from './AddServerDialog';
import ApiKeySettings from './ApiKeySettings';
import ServersList from './ServersList';
import { SettingsPageAction } from './layout/SettingsPageFrame';
import { SettingsSections } from './layout/SettingsSections';

export default function MinecraftServersSettings() {
  return (
    <>
      <SettingsPageAction>
        <AddServerDialog />
      </SettingsPageAction>
      <SettingsSections>
        <ServersList />
        <ApiKeySettings />
      </SettingsSections>
    </>
  );
}
