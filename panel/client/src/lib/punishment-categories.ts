import type { PunishmentTypeConfig } from '@/lib/punishment-preview';
import { MANUAL_PUNISHMENT_LIST } from '@/lib/manual-punishments';
import { punishmentApplyPermission } from '@/lib/permissions';

export interface PunishmentTypesByCategory {
  readonly Administrative: PunishmentTypeConfig[];
  readonly Social: PunishmentTypeConfig[];
  readonly Gameplay: PunishmentTypeConfig[];
}

const DEFAULT_ADMINISTRATIVE_TYPES: readonly PunishmentTypeConfig[] = MANUAL_PUNISHMENT_LIST.map(({ name, ordinal }) => ({
  id: ordinal,
  name,
  category: 'Administrative',
  customizable: false,
  ordinal,
}));

function byOrdinal(a: PunishmentTypeConfig, b: PunishmentTypeConfig): number {
  return (a.ordinal ?? 0) - (b.ordinal ?? 0);
}

function inCategory(types: readonly PunishmentTypeConfig[], category: string): PunishmentTypeConfig[] {
  return types.filter((type) => type.category?.trim().toLowerCase() === category);
}

export function categorizePunishmentTypes(types: readonly PunishmentTypeConfig[]): PunishmentTypesByCategory {
  const configuredAdministrative = inCategory(types, 'administrative');
  const configuredOrdinals = new Set(types.map((type) => type.ordinal));
  const missingDefaults = DEFAULT_ADMINISTRATIVE_TYPES.filter((type) => !configuredOrdinals.has(type.ordinal));
  return {
    Administrative: [...configuredAdministrative, ...missingDefaults].sort(byOrdinal),
    Social: inCategory(types, 'social').sort(byOrdinal),
    Gameplay: inCategory(types, 'gameplay').sort(byOrdinal),
  };
}

export function allPunishmentTypes(byCategory: PunishmentTypesByCategory): PunishmentTypeConfig[] {
  return [...byCategory.Administrative, ...byCategory.Social, ...byCategory.Gameplay];
}

export function permittedPunishmentTypes(
  byCategory: PunishmentTypesByCategory,
  hasPermission: (permission: string) => boolean,
): PunishmentTypesByCategory {
  const permitted = (types: readonly PunishmentTypeConfig[]) =>
    types.filter(({ ordinal }) => ordinal !== undefined && hasPermission(punishmentApplyPermission(ordinal)));
  return {
    Administrative: permitted(byCategory.Administrative),
    Social: permitted(byCategory.Social),
    Gameplay: permitted(byCategory.Gameplay),
  };
}
