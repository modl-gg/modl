import { classifyAiSaveFailure, type AiFailureId, type AiReadinessId, type AiSaveErrorId } from '@/hooks/data/ai';

export const READINESS_MESSAGES: Record<Exclude<AiReadinessId, 'ready'>, string> = {
  notConfigured: 'settings.ai.readiness.notConfigured',
  keyMissing: 'settings.ai.readiness.keyMissing',
  modelMissing: 'settings.ai.readiness.modelMissing',
  baseUrlInvalid: 'settings.ai.readiness.baseUrlInvalid',
  configProviderMissing: 'settings.ai.readiness.configProviderMissing',
  configProviderUnknown: 'settings.ai.readiness.configProviderUnknown',
};

export const FAILURE_MESSAGES: Record<AiFailureId, string> = {
  auth: 'settings.ai.failure.auth',
  permission: 'settings.ai.failure.permission',
  quota: 'settings.ai.failure.quota',
  rateLimited: 'settings.ai.failure.rateLimited',
  modelNotFound: 'settings.ai.failure.modelNotFound',
  invalidRequest: 'settings.ai.failure.invalidRequest',
  connect: 'settings.ai.failure.connect',
  tls: 'settings.ai.failure.tls',
  timeout: 'settings.ai.failure.timeout',
  blockedAddress: 'settings.ai.failure.blockedAddress',
  redirect: 'settings.ai.failure.redirect',
  responseTooLarge: 'settings.ai.failure.responseTooLarge',
  invalidResponse: 'settings.ai.failure.invalidResponse',
  providerError: 'settings.ai.failure.providerError',
  truncated: 'settings.ai.failure.truncated',
  refused: 'settings.ai.failure.refused',
  filtered: 'settings.ai.failure.filtered',
  listUnsupported: 'settings.ai.failure.listUnsupported',
  busy: 'settings.ai.failure.busy',
  hourlyBudget: 'settings.ai.failure.hourlyBudget',
  dailyBudget: 'settings.ai.failure.dailyBudget',
  reporterLimit: 'settings.ai.failure.reporterLimit',
};

const ERROR_MESSAGES: Record<AiSaveErrorId, string> = {
  baseUrlInvalid: 'settings.ai.error.baseUrlInvalid',
  baseUrlHttpPublic: 'settings.ai.error.baseUrlHttpPublic',
  addressBlocked: 'settings.ai.error.addressBlocked',
  keyInvalid: 'settings.ai.error.keyInvalid',
  modelInvalid: 'settings.ai.error.modelInvalid',
  notReady: 'settings.ai.error.notReady',
  providerUnknown: 'settings.ai.error.providerUnknown',
  managedByConfig: 'settings.ai.error.managedByConfig',
};

export function aiErrorMessageKey(error: unknown, fallbackKey: string): string {
  const reason = classifyAiSaveFailure(error);
  return reason === null ? fallbackKey : ERROR_MESSAGES[reason];
}
