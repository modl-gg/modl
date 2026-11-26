import { lazy, type ComponentType } from 'react';
import type { SettingsPageId } from '@/lib/settings-access';

const CustomCssSettings = lazy(async () => import('./CustomCssSettings'));
const DatabaseSettings = lazy(async () => import('./DatabaseSettings'));
const DataRetentionSettings = lazy(async () => import('./DataRetentionSettings'));
const HomepageSettingsPage = lazy(async () => import('./HomepageSettingsPage'));
const KnowledgebaseSettings = lazy(async () => import('./KnowledgebaseSettings'));
const MailSettingsCard = lazy(async () => import('./MailSettingsCard'));
const MinecraftServersSettings = lazy(async () => import('./MinecraftServersSettings'));
const PanelSettings = lazy(async () => import('./PanelSettings'));
const PlayerAddressSettings = lazy(async () => import('./PlayerAddressSettings'));
const PublicThemeSettings = lazy(async () => import('./PublicThemeSettings'));
const ProfileSettings = lazy(async () => import('./ProfileSettings'));
const EscalationSettings = lazy(async () => import('./PunishmentSettings').then((module) => ({ default: module.EscalationSettings })));
const PunishmentTypesSettings = lazy(async () => import('./PunishmentSettings').then((module) => ({ default: module.PunishmentTypesSettings })));
const SecuritySettings = lazy(async () => import('./SecuritySettings'));
const SignInSettingsCard = lazy(async () => import('./SignInSettingsCard').then((module) => ({ default: module.SignInSettingsCard })));
const StaffManagementPanel = lazy(async () => import('./StaffManagementPanel'));
const StaffRolesCard = lazy(async () => import('./StaffRolesCard'));
const StatusSettings = lazy(async () => import('./StatusSettings'));
const StorageSettings = lazy(async () => import('./StorageSettings'));
const TicketAiModerationSettings = lazy(async () => import('./TicketAiModerationSettings'));
const ReplaySettings = lazy(async () => import('./ReplaySettings'));
const QuickRepliesSettings = lazy(async () => import('./TicketSettings').then((module) => ({ default: module.QuickRepliesSettings })));
const TicketFormsSettings = lazy(async () => import('./TicketSettings').then((module) => ({ default: module.TicketFormsSettings })));
const TicketLabelsSettings = lazy(async () => import('./TicketSettings').then((module) => ({ default: module.TicketLabelsSettings })));
const UpdatesSettings = lazy(async () => import('./UpdatesSettings'));
const WebhookSettings = lazy(async () => import('./WebhookSettings'));

export const SETTINGS_PAGE_VIEWS: Record<SettingsPageId, ComponentType> = {
  profile: ProfileSettings,
  security: SecuritySettings,
  panel: PanelSettings,
  'minecraft-servers': MinecraftServersSettings,
  email: MailSettingsCard,
  webhooks: WebhookSettings,
  members: StaffManagementPanel,
  roles: StaffRolesCard,
  'sign-in': SignInSettingsCard,
  'punishment-types': PunishmentTypesSettings,
  escalation: EscalationSettings,
  'player-addresses': PlayerAddressSettings,
  replays: ReplaySettings,
  forms: TicketFormsSettings,
  labels: TicketLabelsSettings,
  'quick-replies': QuickRepliesSettings,
  'ai-moderation': TicketAiModerationSettings,
  homepage: HomepageSettingsPage,
  knowledgebase: KnowledgebaseSettings,
  theme: PublicThemeSettings,
  'custom-css': CustomCssSettings,
  storage: StorageSettings,
  database: DatabaseSettings,
  'data-retention': DataRetentionSettings,
  updates: UpdatesSettings,
  status: StatusSettings,
};
