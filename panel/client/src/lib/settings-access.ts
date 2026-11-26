import { PERMISSIONS } from './permissions.ts';

export interface SettingsAccess {
  isSuperAdmin: boolean;
  hasPermission: (permissionId: string) => boolean;
}

type AccessRule = (access: SettingsAccess) => boolean;

const everyone: AccessRule = () => true;
const superAdminOnly: AccessRule = (access) => access.isSuperAdmin;
const anyOf = (...permissionIds: string[]): AccessRule => (access) =>
  access.isSuperAdmin || permissionIds.some((permissionId) => access.hasPermission(permissionId));

const settingsViewers = anyOf(PERMISSIONS.ADMIN_SETTINGS_VIEW);
const punishmentConfigViewers = anyOf(PERMISSIONS.ADMIN_SETTINGS_VIEW, PERMISSIONS.ADMIN_SETTINGS_VIEW_PUNISHMENTS);
const contentViewers = anyOf(PERMISSIONS.ADMIN_SETTINGS_VIEW, PERMISSIONS.ADMIN_SETTINGS_VIEW_CONTENT);
const storageViewers = anyOf(PERMISSIONS.ADMIN_SETTINGS_VIEW, PERMISSIONS.ADMIN_SETTINGS_VIEW_STORAGE);
const settingsEditors = anyOf(PERMISSIONS.ADMIN_SETTINGS_MODIFY);
const punishmentConfigEditors = anyOf(PERMISSIONS.ADMIN_SETTINGS_MODIFY, PERMISSIONS.ADMIN_SETTINGS_MODIFY_PUNISHMENTS);
const contentEditors = anyOf(PERMISSIONS.ADMIN_SETTINGS_MODIFY, PERMISSIONS.ADMIN_SETTINGS_MODIFY_CONTENT);
const storageEditors = anyOf(PERMISSIONS.ADMIN_SETTINGS_MODIFY, PERMISSIONS.ADMIN_SETTINGS_MODIFY_STORAGE);
const memberManagers = anyOf(PERMISSIONS.ADMIN_STAFF_MANAGE_MEMBERS);
const roleManagers = anyOf(PERMISSIONS.ADMIN_STAFF_MANAGE_ROLES);

export const SETTINGS_PAGE_IDS = [
  'profile',
  'security',
  'panel',
  'minecraft-servers',
  'email',
  'webhooks',
  'members',
  'roles',
  'sign-in',
  'punishment-types',
  'escalation',
  'player-addresses',
  'replays',
  'forms',
  'labels',
  'quick-replies',
  'ai-moderation',
  'homepage',
  'knowledgebase',
  'theme',
  'custom-css',
  'storage',
  'database',
  'data-retention',
  'updates',
  'status',
] as const;

export type SettingsPageId = (typeof SETTINGS_PAGE_IDS)[number];

export const SETTINGS_GROUP_IDS = ['account', 'general', 'staff', 'moderation', 'tickets', 'public-site', 'system'] as const;

export type SettingsGroupId = (typeof SETTINGS_GROUP_IDS)[number];

export interface SettingsPageDefinition {
  id: SettingsPageId;
  canAccess: AccessRule;
  canModify: AccessRule;
}

export interface SettingsGroupDefinition {
  id: SettingsGroupId;
  pages: readonly SettingsPageDefinition[];
}

export const SETTINGS_GROUPS: readonly SettingsGroupDefinition[] = [
  {
    id: 'account',
    pages: [
      { id: 'profile', canAccess: everyone, canModify: everyone },
      { id: 'security', canAccess: everyone, canModify: everyone },
    ],
  },
  {
    id: 'general',
    pages: [
      { id: 'panel', canAccess: superAdminOnly, canModify: superAdminOnly },
      { id: 'minecraft-servers', canAccess: superAdminOnly, canModify: superAdminOnly },
      { id: 'email', canAccess: superAdminOnly, canModify: superAdminOnly },
      { id: 'webhooks', canAccess: settingsViewers, canModify: settingsEditors },
    ],
  },
  {
    id: 'staff',
    pages: [
      { id: 'members', canAccess: memberManagers, canModify: memberManagers },
      { id: 'roles', canAccess: roleManagers, canModify: roleManagers },
      { id: 'sign-in', canAccess: superAdminOnly, canModify: superAdminOnly },
    ],
  },
  {
    id: 'moderation',
    pages: [
      { id: 'punishment-types', canAccess: punishmentConfigViewers, canModify: punishmentConfigEditors },
      { id: 'escalation', canAccess: punishmentConfigViewers, canModify: punishmentConfigEditors },
      { id: 'player-addresses', canAccess: superAdminOnly, canModify: superAdminOnly },
      { id: 'replays', canAccess: settingsViewers, canModify: settingsEditors },
    ],
  },
  {
    id: 'tickets',
    pages: [
      { id: 'forms', canAccess: settingsViewers, canModify: settingsEditors },
      { id: 'labels', canAccess: settingsViewers, canModify: settingsEditors },
      { id: 'quick-replies', canAccess: settingsViewers, canModify: settingsEditors },
      { id: 'ai-moderation', canAccess: settingsViewers, canModify: settingsEditors },
    ],
  },
  {
    id: 'public-site',
    pages: [
      { id: 'homepage', canAccess: contentViewers, canModify: contentEditors },
      { id: 'knowledgebase', canAccess: contentViewers, canModify: contentEditors },
      { id: 'theme', canAccess: contentViewers, canModify: contentEditors },
      { id: 'custom-css', canAccess: superAdminOnly, canModify: superAdminOnly },
    ],
  },
  {
    id: 'system',
    pages: [
      { id: 'storage', canAccess: storageViewers, canModify: storageEditors },
      { id: 'database', canAccess: superAdminOnly, canModify: superAdminOnly },
      { id: 'data-retention', canAccess: settingsViewers, canModify: superAdminOnly },
      { id: 'updates', canAccess: superAdminOnly, canModify: superAdminOnly },
      { id: 'status', canAccess: superAdminOnly, canModify: superAdminOnly },
    ],
  },
];

export const DEFAULT_SETTINGS_PAGE: SettingsPageId = 'profile';

export const SETTINGS_ROOT_PATH = '/panel/settings';

export interface AccessibleSettingsGroup {
  id: SettingsGroupId;
  pages: SettingsPageId[];
}

export function isSettingsPageId(value: string): value is SettingsPageId {
  return SETTINGS_PAGE_IDS.some((pageId) => pageId === value);
}

export function settingsPagePath(pageId: SettingsPageId): string {
  return `${SETTINGS_ROOT_PATH}/${pageId}`;
}

function pageDefinition(pageId: SettingsPageId): SettingsPageDefinition | undefined {
  return SETTINGS_GROUPS.flatMap((group) => group.pages).find((page) => page.id === pageId);
}

export function canAccessSettingsPage(pageId: SettingsPageId, access: SettingsAccess): boolean {
  return pageDefinition(pageId)?.canAccess(access) ?? false;
}

export function canModifySettingsPage(pageId: SettingsPageId, access: SettingsAccess): boolean {
  return pageDefinition(pageId)?.canModify(access) ?? false;
}

export function accessibleSettingsGroups(access: SettingsAccess): AccessibleSettingsGroup[] {
  return SETTINGS_GROUPS
    .map((group) => ({ id: group.id, pages: group.pages.filter((page) => page.canAccess(access)).map((page) => page.id) }))
    .filter((group) => group.pages.length > 0);
}

export type SettingsPageResolution =
  | { kind: 'page'; pageId: SettingsPageId }
  | { kind: 'redirect'; pageId: SettingsPageId };

export function resolveSettingsPage(requested: string | undefined, access: SettingsAccess): SettingsPageResolution {
  if (requested !== undefined && isSettingsPageId(requested) && canAccessSettingsPage(requested, access)) return { kind: 'page', pageId: requested };
  return { kind: 'redirect', pageId: DEFAULT_SETTINGS_PAGE };
}

const LEGACY_CATEGORY_ALIASES: Readonly<Record<string, string>> = {
  tags: 'tickets',
  homepage: 'knowledgebase',
};

const LEGACY_SECTION_PAGES: Readonly<Record<string, Readonly<Record<string, SettingsPageId>>>> = {
  account: {},
  general: {
    'server-config': 'panel',
    'api-key': 'minecraft-servers',
    'sign-in': 'sign-in',
    email: 'email',
    webhooks: 'webhooks',
    'data-retention': 'data-retention',
    storage: 'storage',
    'player-addresses': 'player-addresses',
    updates: 'updates',
    status: 'status',
  },
  punishment: {
    thresholds: 'escalation',
    types: 'punishment-types',
  },
  tickets: {
    'quick-responses': 'quick-replies',
    'label-management': 'labels',
    'ticket-forms': 'forms',
    'ai-moderation': 'ai-moderation',
  },
  staff: {
    'staff-management': 'members',
    'roles-permissions': 'roles',
  },
  knowledgebase: {
    'knowledgebase-articles': 'knowledgebase',
    'homepage-cards': 'homepage',
  },
};

const LEGACY_CATEGORY_PAGES: Readonly<Record<string, SettingsPageId>> = {
  account: 'profile',
  general: 'panel',
  punishment: 'punishment-types',
  tickets: 'forms',
  staff: 'members',
  knowledgebase: 'knowledgebase',
};

function ownEntry<Value>(record: Readonly<Record<string, Value>>, key: string): Value | undefined {
  return Object.hasOwn(record, key) ? record[key] : undefined;
}

function legacyParameter(search: URLSearchParams, names: readonly string[]): string | null {
  for (const name of names) {
    const value = search.get(name);
    if (value !== null && value !== '') return value.split(',')[0] ?? value;
  }
  return null;
}

export function legacySettingsPage(search: string): SettingsPageId | null {
  const params = new URLSearchParams(search);
  const requestedCategory = legacyParameter(params, ['category', 'tab']);
  if (requestedCategory === null) return null;
  const category = ownEntry(LEGACY_CATEGORY_ALIASES, requestedCategory) ?? requestedCategory;
  const section = legacyParameter(params, ['sub', 'section']);
  const sections = ownEntry(LEGACY_SECTION_PAGES, category);
  const sectionPage = section === null || sections === undefined ? undefined : ownEntry(sections, section);
  return sectionPage ?? ownEntry(LEGACY_CATEGORY_PAGES, category) ?? DEFAULT_SETTINGS_PAGE;
}

export const SETTINGS_DATA_CONSUMERS = {
  general: ['panel', 'webhooks'],
  punishmentTypes: ['punishment-types', 'quick-replies', 'ai-moderation'],
  statusThresholds: ['escalation'],
  quickResponses: ['quick-replies'],
  ticketLabels: ['labels'],
  ticketForms: ['forms'],
} as const satisfies Record<string, readonly SettingsPageId[]>;

export type SettingsData = keyof typeof SETTINGS_DATA_CONSUMERS;

export function shouldLoadSettingsData(data: SettingsData, access: SettingsAccess): boolean {
  const consumers: readonly SettingsPageId[] = SETTINGS_DATA_CONSUMERS[data];
  return consumers.some((pageId) => canAccessSettingsPage(pageId, access));
}
