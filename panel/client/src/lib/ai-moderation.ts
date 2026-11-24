import { apiErrorCode } from './http-error.ts';

export const AI_PUNISHMENT_TYPE_INELIGIBLE_CODE = 'AI_PUNISHMENT_TYPE_INELIGIBLE';

export interface AiPickableType {
  ordinal?: number | undefined;
  acceptsAiSuggestions?: boolean | undefined;
}

export function aiPunishmentConfigKey(type: AiPickableType): string | null {
  return type.ordinal === undefined ? null : String(type.ordinal);
}

export function selectableAiPunishmentTypes<T extends AiPickableType>(types: readonly T[], configuredKeys: readonly string[]): T[] {
  const configured = new Set(configuredKeys);
  return types.filter((type) => {
    const key = aiPunishmentConfigKey(type);
    return type.acceptsAiSuggestions === true && key !== null && !configured.has(key);
  });
}

export function isAiPunishmentTypeIneligible(error: unknown): boolean {
  return apiErrorCode(error) === AI_PUNISHMENT_TYPE_INELIGIBLE_CODE;
}

export function canToggleAiReview(enabled: boolean, providerReady: boolean): boolean {
  return enabled || providerReady;
}
