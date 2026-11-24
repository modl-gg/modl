import { useTranslation } from 'react-i18next';
import { useAiModerationSettings, useSaveAiModerationSettings, type AiModerationConfig } from '@/hooks/data/settings';
import { PERMISSIONS, usePermissions } from '@/hooks/use-permissions';
import { isAiPunishmentTypeIneligible } from '@/lib/ai-moderation';
import { useVersionedSettingsDraft } from './save/use-settings-draft';

const DEFAULT_AI_MODERATION_SETTINGS: AiModerationConfig = {
  enableAIReview: false,
  enableAutomatedActions: false,
  aiPunishmentConfigs: {},
};

export function useAiModerationDraft() {
  const { t } = useTranslation();
  const { hasPermission } = usePermissions();
  const query = useAiModerationSettings({ enabled: hasPermission(PERMISSIONS.ADMIN_SETTINGS_VIEW) });
  const saveSettings = useSaveAiModerationSettings();

  const draft = useVersionedSettingsDraft({
    persisted: query.data,
    fallback: DEFAULT_AI_MODERATION_SETTINGS,
    save: saveSettings.mutateAsync,
    describeRejection: (error) => (isAiPunishmentTypeIneligible(error) ? t('settings.tickets.aiPunishmentTypeIneligible') : null),
  });
  return { draft, isLoading: query.isPending };
}
