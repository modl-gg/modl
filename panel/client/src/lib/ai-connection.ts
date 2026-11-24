import type {
  AiConnectionInfo,
  AiConnectionSave,
  AiDraftInput,
  AiLimitsValue,
  AiModelOption,
  AiProvider,
} from '@/hooks/data/ai';

export interface AiDraftEdits {
  baseUrl?: string;
  apiKey?: string;
  model?: string;
  clearKey?: boolean;
}

export interface AiDraft {
  provider: AiProvider;
  saved: AiConnectionInfo | null;
  baseUrl: string;
  apiKey: string;
  model: string;
  storedKeyApplies: boolean;
  storedKeyDropped: boolean;
  dirty: boolean;
}

export type AiLimitField = keyof AiLimitsValue;

export const AI_LIMIT_BOUNDS: Readonly<Record<AiLimitField, { min: number; max: number }>> = {
  callsPerHour: { min: 1, max: 10_000 },
  callsPerDay: { min: 1, max: 100_000 },
  reportsPerReporterPerHour: { min: 1, max: 1_000 },
  concurrentCalls: { min: 1, max: 16 },
  maxOutputTokens: { min: 256, max: 32_768 },
};

export const AI_LIMIT_FIELDS: readonly AiLimitField[] = [
  'callsPerHour',
  'callsPerDay',
  'reportsPerReporterPerHour',
  'concurrentCalls',
  'maxOutputTokens',
];

const DEFAULT_PORTS: Readonly<Record<string, string>> = { 'http:': '80', 'https:': '443' };

export function normalizedBaseUrl(raw: string): string {
  const trimmed = raw.trim();
  try {
    const url = new URL(trimmed);
    const port = url.port === '' || DEFAULT_PORTS[url.protocol] === url.port ? '' : `:${url.port}`;
    const path = url.pathname.replace(/\/+$/u, '');
    return `${url.protocol}//${url.hostname.toLowerCase()}${port}${path}`;
  } catch {
    return trimmed.replace(/\/+$/u, '');
  }
}

export function savedConnectionFor(connections: readonly AiConnectionInfo[], providerId: string): AiConnectionInfo | null {
  return connections.find((connection) => connection.provider === providerId) ?? null;
}

export function draftOf(provider: AiProvider, saved: AiConnectionInfo | null, edits: AiDraftEdits | null): AiDraft {
  const baseUrl = edits?.baseUrl ?? saved?.baseUrl ?? provider.endpoints[0]?.baseUrl ?? '';
  const urlUnchanged = saved !== null && normalizedBaseUrl(baseUrl) === normalizedBaseUrl(saved.baseUrl);
  const keyWasSaved = saved?.hasApiKey ?? false;
  const storedKeyApplies = keyWasSaved && urlUnchanged && edits?.clearKey !== true;
  return {
    provider,
    saved,
    baseUrl,
    apiKey: edits?.apiKey ?? '',
    model: edits?.model ?? saved?.model ?? '',
    storedKeyApplies,
    storedKeyDropped: keyWasSaved && !urlUnchanged,
    dirty: edits !== null && (edits.baseUrl !== undefined || edits.apiKey !== undefined || edits.model !== undefined
      || edits.clearKey === true),
  };
}

export function hasUsableKey(draft: AiDraft): boolean {
  return draft.apiKey.trim().length > 0 || draft.storedKeyApplies || !draft.provider.apiKeyRequired;
}

export function canSave(draft: AiDraft): boolean {
  return draft.baseUrl.trim().length > 0 && draft.model.trim().length > 0 && hasUsableKey(draft);
}

export function canListModels(draft: AiDraft): boolean {
  return draft.baseUrl.trim().length > 0 && hasUsableKey(draft);
}

export function saveRequest(draft: AiDraft, edits: AiDraftEdits | null, activate: boolean): AiConnectionSave {
  const typedKey = draft.apiKey.trim();
  const request: AiConnectionSave = { provider: draft.provider.id, activate };
  if (edits?.baseUrl !== undefined) request.baseUrl = draft.baseUrl.trim();
  if (typedKey.length > 0) {
    request.apiKey = typedKey;
  } else if (edits?.clearKey === true) {
    request.apiKey = '';
  }
  if (edits?.model !== undefined) request.model = draft.model.trim();
  return request;
}

export function draftRequest(draft: AiDraft): AiDraftInput {
  const request: AiDraftInput = { provider: draft.provider.id, baseUrl: draft.baseUrl.trim() };
  const typedKey = draft.apiKey.trim();
  if (typedKey.length > 0) request.apiKey = typedKey;
  const model = draft.model.trim();
  if (model.length > 0) request.model = model;
  return request;
}

export function visibleModels(models: readonly AiModelOption[], query: string, showAll: boolean): AiModelOption[] {
  const needle = query.trim().toLowerCase();
  return models.filter((model) => (showAll || model.chatCapable)
    && (needle.length === 0
      || model.id.toLowerCase().includes(needle)
      || (model.displayName ?? '').toLowerCase().includes(needle)));
}

export function isTypedModelNew(models: readonly AiModelOption[], query: string): boolean {
  const typed = query.trim();
  return typed.length > 0 && !models.some((model) => model.id === typed);
}

export function parsedLimit(field: AiLimitField, raw: string): number | null {
  if (!/^\d{1,6}$/u.test(raw.trim())) return null;
  const value = Number(raw.trim());
  const bounds = AI_LIMIT_BOUNDS[field];
  return value >= bounds.min && value <= bounds.max ? value : null;
}

export function limitsFrom(raw: Readonly<Record<AiLimitField, string>>): AiLimitsValue | null {
  const callsPerHour = parsedLimit('callsPerHour', raw.callsPerHour);
  const callsPerDay = parsedLimit('callsPerDay', raw.callsPerDay);
  const reportsPerReporterPerHour = parsedLimit('reportsPerReporterPerHour', raw.reportsPerReporterPerHour);
  const concurrentCalls = parsedLimit('concurrentCalls', raw.concurrentCalls);
  const maxOutputTokens = parsedLimit('maxOutputTokens', raw.maxOutputTokens);
  if (callsPerHour === null || callsPerDay === null || reportsPerReporterPerHour === null || concurrentCalls === null
    || maxOutputTokens === null) {
    return null;
  }
  return { callsPerHour, callsPerDay, reportsPerReporterPerHour, concurrentCalls, maxOutputTokens };
}
