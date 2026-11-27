import type { WikiLink } from './wiki-links.ts';
import type { SettingsPageId } from './settings-access.ts';
import type { SetupStepId } from './setup-steps.ts';

export const SETTINGS_PAGE_HELP: Readonly<Partial<Record<SettingsPageId, WikiLink>>> = {
  'minecraft-servers': { page: 'Proxy-Network' },
  email: { page: 'Email-and-Sign-in', section: 'email-smtp' },
  webhooks: { page: 'Placeholders', section: 'panel' },
  'sign-in': { page: 'Email-and-Sign-in', section: 'sign-in-methods' },
  'player-addresses': { page: 'Configuration', section: 'player-addresses' },
  replays: { page: 'Replays', section: 'settings' },
  'ai-moderation': { page: 'AI-Moderation' },
  homepage: { page: 'Customizing-the-Public-Pages', section: 'homepage' },
  theme: { page: 'Customizing-the-Public-Pages', section: 'theme' },
  'custom-css': { page: 'Custom-CSS' },
  storage: { page: 'Database-and-Storage', section: 'uploads' },
  database: { page: 'Database-and-Storage', section: 'mysql-mariadb-postgresql' },
  'data-retention': { page: 'Database-and-Storage', section: 'retention' },
  updates: { page: 'Updating' },
};

export const SETUP_STEP_HELP: Readonly<Partial<Record<SetupStepId, WikiLink>>> = {
  import: { page: 'Importing', section: 'from-modlgg' },
  address: { page: 'Panel-Port-and-Tunnels' },
  email: { page: 'Email-and-Sign-in', section: 'email-smtp' },
  servers: { page: 'Proxy-Network' },
  'player-addresses': { page: 'Configuration', section: 'player-addresses' },
  ai: { page: 'AI-Moderation' },
  replays: { page: 'Replays', section: 'settings' },
};
