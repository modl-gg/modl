export type PunishmentCategoryId = 'gameplay' | 'social' | 'administrative';

export const PUNISHMENT_CATEGORY_LABEL_KEYS: Readonly<Record<PunishmentCategoryId, string>> = {
  gameplay: 'punishment.categories.gameplay',
  social: 'punishment.categories.social',
  administrative: 'punishment.categories.administrative',
};

function isPunishmentCategoryId(value: string): value is PunishmentCategoryId {
  return Object.hasOwn(PUNISHMENT_CATEGORY_LABEL_KEYS, value);
}

export function punishmentCategoryLabelKey(category: string): string | undefined {
  const id = category.trim().toLowerCase();
  return isPunishmentCategoryId(id) ? PUNISHMENT_CATEGORY_LABEL_KEYS[id] : undefined;
}
