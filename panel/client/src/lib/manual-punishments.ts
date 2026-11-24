export type ManualPunishmentName = 'Kick' | 'Manual Mute' | 'Manual Ban' | 'Security Ban' | 'Linked Ban' | 'Blacklist';

export type ManualPunishmentAction = 'kick' | 'mute' | 'ban' | 'blacklist';

export type ManualDurationMode = 'none' | 'staffChosen' | 'permanent';

export type ManualPunishmentToggle = 'kickSameIP' | 'banLinkedAccounts' | 'wipeAccount';

export type PunishmentOptionsLayout =
  | { readonly kind: 'none' }
  | { readonly kind: 'linkedBanSearch' }
  | { readonly kind: 'configured' }
  | { readonly kind: 'toggles'; readonly toggles: readonly ManualPunishmentToggle[] };

export interface ManualPunishmentDescriptor {
  readonly name: ManualPunishmentName;
  readonly ordinal: number;
  readonly action: ManualPunishmentAction;
  readonly durationMode: ManualDurationMode;
  readonly reasonPlaceholderKey: string | null;
  readonly requiresOnlinePlayer: boolean;
  readonly configurableInSettings: boolean;
  readonly options: PunishmentOptionsLayout;
}

type ManualPunishmentTable = {
  readonly [Name in ManualPunishmentName]: ManualPunishmentDescriptor & { readonly name: Name };
};

const CONFIGURED_OPTIONS: PunishmentOptionsLayout = { kind: 'configured' };

export const MANUAL_PUNISHMENTS = {
  Kick: {
    name: 'Kick',
    ordinal: 0,
    action: 'kick',
    durationMode: 'none',
    reasonPlaceholderKey: 'punishment.reasonPlaceholderKick',
    requiresOnlinePlayer: true,
    configurableInSettings: false,
    options: { kind: 'toggles', toggles: ['kickSameIP'] },
  },
  'Manual Mute': {
    name: 'Manual Mute',
    ordinal: 1,
    action: 'mute',
    durationMode: 'staffChosen',
    reasonPlaceholderKey: 'punishment.reasonPlaceholderMute',
    requiresOnlinePlayer: false,
    configurableInSettings: true,
    options: CONFIGURED_OPTIONS,
  },
  'Manual Ban': {
    name: 'Manual Ban',
    ordinal: 2,
    action: 'ban',
    durationMode: 'staffChosen',
    reasonPlaceholderKey: 'punishment.reasonPlaceholderBan',
    requiresOnlinePlayer: false,
    configurableInSettings: true,
    options: { kind: 'toggles', toggles: ['banLinkedAccounts', 'wipeAccount'] },
  },
  'Security Ban': {
    name: 'Security Ban',
    ordinal: 3,
    action: 'ban',
    durationMode: 'none',
    reasonPlaceholderKey: null,
    requiresOnlinePlayer: false,
    configurableInSettings: true,
    options: { kind: 'none' },
  },
  'Linked Ban': {
    name: 'Linked Ban',
    ordinal: 4,
    action: 'ban',
    durationMode: 'none',
    reasonPlaceholderKey: null,
    requiresOnlinePlayer: false,
    configurableInSettings: true,
    options: { kind: 'linkedBanSearch' },
  },
  Blacklist: {
    name: 'Blacklist',
    ordinal: 5,
    action: 'blacklist',
    durationMode: 'permanent',
    reasonPlaceholderKey: null,
    requiresOnlinePlayer: false,
    configurableInSettings: false,
    options: { kind: 'none' },
  },
} as const satisfies ManualPunishmentTable;

export const MANUAL_PUNISHMENT_LIST: readonly ManualPunishmentDescriptor[] = Object.values(MANUAL_PUNISHMENTS);

const MANUAL_PUNISHMENTS_BY_NAME: ReadonlyMap<string, ManualPunishmentDescriptor> = new Map(
  MANUAL_PUNISHMENT_LIST.map((descriptor) => [descriptor.name, descriptor]),
);

const MANUAL_PUNISHMENTS_BY_ORDINAL: ReadonlyMap<number, ManualPunishmentDescriptor> = new Map(
  MANUAL_PUNISHMENT_LIST.map((descriptor) => [descriptor.ordinal, descriptor]),
);

const MANUAL_PUNISHMENT_ORDINALS: ReadonlySet<number> = new Set(MANUAL_PUNISHMENTS_BY_ORDINAL.keys());

const LAST_MANUAL_PUNISHMENT_ORDINAL = Math.max(...MANUAL_PUNISHMENT_ORDINALS);

export function manualPunishmentOf(punishmentTypeName: string | undefined): ManualPunishmentDescriptor | undefined {
  return punishmentTypeName === undefined ? undefined : MANUAL_PUNISHMENTS_BY_NAME.get(punishmentTypeName);
}

export function isManualPunishment(punishmentTypeName: string | undefined): boolean {
  return manualPunishmentOf(punishmentTypeName) !== undefined;
}

export function isManualPunishmentOrdinal(ordinal: number): boolean {
  return MANUAL_PUNISHMENT_ORDINALS.has(ordinal);
}

export function isConfigurableInSettings(ordinal: number): boolean {
  return MANUAL_PUNISHMENTS_BY_ORDINAL.get(ordinal)?.configurableInSettings ?? true;
}

export function isCustomPunishmentOrdinal(ordinal: number): boolean {
  return ordinal > LAST_MANUAL_PUNISHMENT_ORDINAL;
}

export function hasStaffChosenDuration(punishmentTypeName: string | undefined): boolean {
  return manualPunishmentOf(punishmentTypeName)?.durationMode === 'staffChosen';
}

export function punishmentOptionsLayout(punishmentTypeName: string): PunishmentOptionsLayout {
  return manualPunishmentOf(punishmentTypeName)?.options ?? CONFIGURED_OPTIONS;
}

export function reasonPlaceholderKey(punishmentTypeName: string | undefined): string | null {
  return manualPunishmentOf(punishmentTypeName)?.reasonPlaceholderKey ?? null;
}

export function requiresPlayerFacingReason(punishmentTypeName: string | undefined): boolean {
  return reasonPlaceholderKey(punishmentTypeName) !== null;
}

export function requiresOnlinePlayer(punishmentTypeName: string): boolean {
  return manualPunishmentOf(punishmentTypeName)?.requiresOnlinePlayer === true;
}

export function playerFacingReason(punishmentTypeName: string | undefined, reason: string | undefined): string | undefined {
  if (!requiresPlayerFacingReason(punishmentTypeName)) {
    return undefined;
  }
  const trimmed = reason?.trim();
  return trimmed ? trimmed : undefined;
}
