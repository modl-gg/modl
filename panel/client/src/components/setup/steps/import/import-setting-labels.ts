const SETTING_KEYS: Readonly<Record<string, string>> = {
  general: 'settings.pages.panel',
  punishmentTypes: 'settings.pages.punishmentTypes',
  statusThresholds: 'settings.pages.escalation',
  ticketForms: 'settings.pages.forms',
  quickResponses: 'settings.pages.quickReplies',
  ticketLabels: 'settings.pages.labels',
  webhookSettings: 'settings.pages.webhooks',
  aiModerationSettings: 'settings.pages.aiModeration',
  replayRetention: 'settings.pages.dataRetention',
};

export const NAMED_SETTINGS: readonly string[] = Object.keys(SETTING_KEYS);

export function settingKey(setting: string): string | undefined {
  return SETTING_KEYS[setting];
}
