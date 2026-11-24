import { useMemo } from 'react';
import type { PunishmentTypeConfig } from '@/lib/punishment-preview';
import { usePermissions } from '@/hooks/use-permissions';
import {
  allPunishmentTypes,
  categorizePunishmentTypes,
  permittedPunishmentTypes,
  type PunishmentTypesByCategory,
} from '@/lib/punishment-categories';

const NO_PUNISHMENT_TYPES: readonly PunishmentTypeConfig[] = [];

export interface PunishmentTypeCatalog {
  readonly punishmentTypes: readonly PunishmentTypeConfig[];
  readonly permittedTypesByCategory: PunishmentTypesByCategory;
}

export function usePunishmentTypeCatalog(configuredTypes: readonly PunishmentTypeConfig[] | undefined): PunishmentTypeCatalog {
  const { hasPermission } = usePermissions();
  const types = configuredTypes ?? NO_PUNISHMENT_TYPES;
  const typesByCategory = useMemo(() => categorizePunishmentTypes(types), [types]);
  const permittedTypesByCategory = useMemo(
    () => permittedPunishmentTypes(typesByCategory, hasPermission),
    [typesByCategory, hasPermission],
  );
  const punishmentTypes = useMemo(() => allPunishmentTypes(typesByCategory), [typesByCategory]);
  return { punishmentTypes, permittedTypesByCategory };
}
