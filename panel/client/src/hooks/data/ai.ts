import { create } from '@bufbuild/protobuf';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  AiConnectionDraftSchema,
  AiEndpointRegion,
  AiFailure,
  AiModelsResponseSchema,
  AiModelWarning,
  AiOutputMode,
  AiProviderGroup,
  AiReadiness,
  AiSettingsResponseSchema,
  AiStatusResponseSchema,
  AiTestResponseSchema,
  ListAiModelsRequestSchema,
  SaveAiConnectionRequestSchema,
  TestAiConnectionRequestSchema,
  UpdateAiSettingsRequestSchema,
  type AiConnectionDraft,
  type AiConnectionInfo as AiConnectionInfoMessage,
  type AiHealth,
  type AiLimits,
  type AiModel,
  type AiModelsResponse,
  type AiProviderInfo,
  type AiSettingsResponse,
  type AiStatusResponse,
  type AiTestResponse,
} from '@proto/modl/v1/ai_pb.ts';
import { apiErrorCode, isConflictError } from '@/lib/http-error';
import { protoFetch, protoSend } from '@/lib/proto-fetch';
import { tsToIso } from '@/lib/proto-ui';
import { optionalField } from '@/lib/defined-fields';

export const AI_SETTINGS_PATH = '/v1/panel/settings/ai';
export const AI_STATUS_PATH = `${AI_SETTINGS_PATH}/status`;
const AI_SETTINGS_QUERY_KEY = [AI_SETTINGS_PATH] as const;
const AI_STATUS_QUERY_KEY = [AI_STATUS_PATH] as const;

export type AiProviderGroupId = 'cloud' | 'gateway' | 'local' | 'custom';
export type AiRegionId = 'global' | 'china' | 'singapore' | 'us';
export type AiReadinessId =
  | 'ready'
  | 'notConfigured'
  | 'keyMissing'
  | 'modelMissing'
  | 'baseUrlInvalid'
  | 'configProviderMissing'
  | 'configProviderUnknown';
export type AiOutputModeId = 'schema' | 'jsonObject' | 'promptOnly';
export type AiFailureId =
  | 'auth'
  | 'permission'
  | 'quota'
  | 'rateLimited'
  | 'modelNotFound'
  | 'invalidRequest'
  | 'connect'
  | 'tls'
  | 'timeout'
  | 'blockedAddress'
  | 'redirect'
  | 'responseTooLarge'
  | 'invalidResponse'
  | 'providerError'
  | 'truncated'
  | 'refused'
  | 'filtered'
  | 'listUnsupported'
  | 'busy'
  | 'hourlyBudget'
  | 'dailyBudget'
  | 'reporterLimit';
export type AiModelWarningId = 'deprecated' | 'retiring' | 'notListed';

export interface AiEndpointOption {
  region: AiRegionId;
  baseUrl: string;
}

export interface AiProvider {
  id: string;
  displayName: string;
  group: AiProviderGroupId;
  endpoints: AiEndpointOption[];
  baseUrlEditable: boolean;
  apiKeyRequired: boolean;
  keyPageUrl: string | null;
}

export interface AiConnectionInfo {
  provider: string;
  baseUrl: string;
  hasApiKey: boolean;
  model: string | null;
  outputMode: AiOutputModeId | null;
  verifiedAt: string | null;
}

export interface AiManaged {
  provider: boolean;
  apiKey: boolean;
  model: boolean;
  baseUrl: boolean;
}

export interface AiLimitsValue {
  callsPerHour: number;
  callsPerDay: number;
  reportsPerReporterPerHour: number;
  concurrentCalls: number;
  maxOutputTokens: number;
}

export interface AiHealthState {
  lastSuccessAt: string | null;
  lastFailure: AiFailureId | null;
  lastFailureAt: string | null;
  pausedUntil: string | null;
  callsThisHour: number;
  callsToday: number;
  modelWarning: AiModelWarningId | null;
  modelRetiresAt: string | null;
}

export interface AiSettingsState {
  providers: AiProvider[];
  activeProvider: string | null;
  connections: AiConnectionInfo[];
  managed: AiManaged;
  readiness: AiReadinessId;
  limits: AiLimitsValue;
  limitsVersion: number;
  health: AiHealthState;
}

export interface AiStatus {
  ready: boolean;
  readiness: AiReadinessId;
  providerName: string | null;
  model: string | null;
}

export interface AiModelOption {
  id: string;
  displayName: string | null;
  chatCapable: boolean;
  deprecated: boolean;
  retiresAt: string | null;
  structuredOutputs: boolean | null;
}

export interface AiModelList {
  models: AiModelOption[];
  stale: boolean;
  fetchedAt: string | null;
  failure: AiFailureId | null;
}

export interface AiTestResult {
  success: boolean;
  failure: AiFailureId | null;
  outputMode: AiOutputModeId | null;
  latencyMs: number;
}

export interface AiDraftInput {
  provider: string;
  baseUrl?: string;
  apiKey?: string;
  model?: string;
}

export interface AiConnectionSave {
  provider: string;
  baseUrl?: string;
  apiKey?: string;
  model?: string;
  activate: boolean;
}

export interface AiLimitsWrite {
  expectedVersion: number;
  limits: AiLimitsValue;
}

export interface AiSettingsUpdate {
  activeProvider?: string;
  limitsWrite?: AiLimitsWrite;
}

export type AiSaveErrorId =
  | 'baseUrlInvalid'
  | 'baseUrlHttpPublic'
  | 'addressBlocked'
  | 'keyInvalid'
  | 'modelInvalid'
  | 'notReady'
  | 'providerUnknown'
  | 'managedByConfig';

const SAVE_ERROR_CODES: ReadonlyMap<string, AiSaveErrorId> = new Map([
  ['AI_BASE_URL_INVALID', 'baseUrlInvalid'],
  ['AI_BASE_URL_HTTP_PUBLIC', 'baseUrlHttpPublic'],
  ['AI_ADDRESS_BLOCKED', 'addressBlocked'],
  ['AI_KEY_INVALID', 'keyInvalid'],
  ['AI_MODEL_INVALID', 'modelInvalid'],
  ['AI_NOT_READY', 'notReady'],
  ['AI_PROVIDER_UNKNOWN', 'providerUnknown'],
  ['AI_MANAGED_BY_CONFIG', 'managedByConfig'],
]);

const GROUPS: Record<AiProviderGroup, AiProviderGroupId> = {
  [AiProviderGroup.UNSPECIFIED]: 'custom',
  [AiProviderGroup.CLOUD]: 'cloud',
  [AiProviderGroup.GATEWAY]: 'gateway',
  [AiProviderGroup.LOCAL]: 'local',
  [AiProviderGroup.CUSTOM]: 'custom',
};

const REGIONS: Record<AiEndpointRegion, AiRegionId> = {
  [AiEndpointRegion.UNSPECIFIED]: 'global',
  [AiEndpointRegion.GLOBAL]: 'global',
  [AiEndpointRegion.CHINA]: 'china',
  [AiEndpointRegion.SINGAPORE]: 'singapore',
  [AiEndpointRegion.US]: 'us',
};

const READINESS: Record<AiReadiness, AiReadinessId> = {
  [AiReadiness.UNSPECIFIED]: 'notConfigured',
  [AiReadiness.READY]: 'ready',
  [AiReadiness.NOT_CONFIGURED]: 'notConfigured',
  [AiReadiness.KEY_MISSING]: 'keyMissing',
  [AiReadiness.MODEL_MISSING]: 'modelMissing',
  [AiReadiness.BASE_URL_INVALID]: 'baseUrlInvalid',
  [AiReadiness.CONFIG_PROVIDER_MISSING]: 'configProviderMissing',
  [AiReadiness.CONFIG_PROVIDER_UNKNOWN]: 'configProviderUnknown',
};

const OUTPUT_MODES: Record<AiOutputMode, AiOutputModeId | null> = {
  [AiOutputMode.UNSPECIFIED]: null,
  [AiOutputMode.SCHEMA]: 'schema',
  [AiOutputMode.JSON_OBJECT]: 'jsonObject',
  [AiOutputMode.PROMPT_ONLY]: 'promptOnly',
};

const FAILURES: Record<AiFailure, AiFailureId | null> = {
  [AiFailure.UNSPECIFIED]: null,
  [AiFailure.AUTH]: 'auth',
  [AiFailure.PERMISSION]: 'permission',
  [AiFailure.QUOTA]: 'quota',
  [AiFailure.RATE_LIMITED]: 'rateLimited',
  [AiFailure.MODEL_NOT_FOUND]: 'modelNotFound',
  [AiFailure.INVALID_REQUEST]: 'invalidRequest',
  [AiFailure.CONNECT]: 'connect',
  [AiFailure.TLS]: 'tls',
  [AiFailure.TIMEOUT]: 'timeout',
  [AiFailure.BLOCKED_ADDRESS]: 'blockedAddress',
  [AiFailure.REDIRECT]: 'redirect',
  [AiFailure.RESPONSE_TOO_LARGE]: 'responseTooLarge',
  [AiFailure.INVALID_RESPONSE]: 'invalidResponse',
  [AiFailure.PROVIDER_ERROR]: 'providerError',
  [AiFailure.TRUNCATED]: 'truncated',
  [AiFailure.REFUSED]: 'refused',
  [AiFailure.FILTERED]: 'filtered',
  [AiFailure.LIST_UNSUPPORTED]: 'listUnsupported',
  [AiFailure.BUSY]: 'busy',
  [AiFailure.HOURLY_BUDGET]: 'hourlyBudget',
  [AiFailure.DAILY_BUDGET]: 'dailyBudget',
  [AiFailure.REPORTER_LIMIT]: 'reporterLimit',
};

const MODEL_WARNINGS: Record<AiModelWarning, AiModelWarningId | null> = {
  [AiModelWarning.UNSPECIFIED]: null,
  [AiModelWarning.DEPRECATED]: 'deprecated',
  [AiModelWarning.RETIRING]: 'retiring',
  [AiModelWarning.NOT_LISTED]: 'notListed',
};

const DEFAULT_LIMITS: AiLimitsValue = {
  callsPerHour: 60,
  callsPerDay: 500,
  reportsPerReporterPerHour: 5,
  concurrentCalls: 2,
  maxOutputTokens: 4096,
};

function toProvider(info: AiProviderInfo): AiProvider {
  return {
    id: info.id,
    displayName: info.displayName,
    group: GROUPS[info.group],
    endpoints: info.endpoints.map((endpoint) => ({ region: REGIONS[endpoint.region], baseUrl: endpoint.baseUrl })),
    baseUrlEditable: info.baseUrlEditable,
    apiKeyRequired: info.apiKeyRequired,
    keyPageUrl: info.keyPageUrl ?? null,
  };
}

function toConnection(info: AiConnectionInfoMessage): AiConnectionInfo {
  return {
    provider: info.provider,
    baseUrl: info.baseUrl,
    hasApiKey: info.hasApiKey,
    model: info.model ?? null,
    outputMode: OUTPUT_MODES[info.outputMode],
    verifiedAt: tsToIso(info.verifiedAt),
  };
}

function toLimits(limits: AiLimits | undefined): AiLimitsValue {
  if (!limits) return DEFAULT_LIMITS;
  return {
    callsPerHour: limits.callsPerHour,
    callsPerDay: limits.callsPerDay,
    reportsPerReporterPerHour: limits.reportsPerReporterPerHour,
    concurrentCalls: limits.concurrentCalls,
    maxOutputTokens: limits.maxOutputTokens,
  };
}

function toHealth(health: AiHealth | undefined): AiHealthState {
  return {
    lastSuccessAt: tsToIso(health?.lastSuccessAt),
    lastFailure: health ? FAILURES[health.lastFailure] : null,
    lastFailureAt: tsToIso(health?.lastFailureAt),
    pausedUntil: tsToIso(health?.pausedUntil),
    callsThisHour: health?.callsThisHour ?? 0,
    callsToday: health?.callsToday ?? 0,
    modelWarning: health ? MODEL_WARNINGS[health.modelWarning] : null,
    modelRetiresAt: tsToIso(health?.modelRetiresAt),
  };
}

function toSettings(response: AiSettingsResponse): AiSettingsState {
  return {
    providers: response.providers.map(toProvider),
    activeProvider: response.activeProvider ?? null,
    connections: response.connections.map(toConnection),
    managed: {
      provider: response.managed?.provider ?? false,
      apiKey: response.managed?.apiKey ?? false,
      model: response.managed?.model ?? false,
      baseUrl: response.managed?.baseUrl ?? false,
    },
    readiness: READINESS[response.readiness],
    limits: toLimits(response.limits),
    limitsVersion: Number(response.limitsVersion),
    health: toHealth(response.health),
  };
}

function toStatus(response: AiStatusResponse): AiStatus {
  return {
    ready: response.ready,
    readiness: READINESS[response.readiness],
    providerName: response.providerName ?? null,
    model: response.model ?? null,
  };
}

function toModel(model: AiModel): AiModelOption {
  return {
    id: model.id,
    displayName: model.displayName ?? null,
    chatCapable: model.chatCapable,
    deprecated: model.deprecated,
    retiresAt: tsToIso(model.retiresAt),
    structuredOutputs: model.structuredOutputs ?? null,
  };
}

function toModelList(response: AiModelsResponse): AiModelList {
  return {
    models: response.models.map(toModel),
    stale: response.stale,
    fetchedAt: tsToIso(response.fetchedAt),
    failure: FAILURES[response.failure],
  };
}

function toTestResult(response: AiTestResponse): AiTestResult {
  return {
    success: response.success,
    failure: FAILURES[response.failure],
    outputMode: OUTPUT_MODES[response.outputMode],
    latencyMs: Number(response.latencyMs),
  };
}

function draftMessage(draft: AiDraftInput): AiConnectionDraft {
  return create(AiConnectionDraftSchema, {
    provider: draft.provider,
    ...optionalField('baseUrl', draft.baseUrl),
    ...optionalField('apiKey', draft.apiKey),
    ...optionalField('model', draft.model),
  });
}

export function classifyAiSaveFailure(error: unknown): AiSaveErrorId | null {
  return SAVE_ERROR_CODES.get(apiErrorCode(error) ?? '') ?? null;
}

export function useAiSettings(enabled: boolean) {
  return useQuery({
    queryKey: AI_SETTINGS_QUERY_KEY,
    queryFn: async () => toSettings(await protoFetch(AiSettingsResponseSchema, AI_SETTINGS_PATH)),
    enabled,
    staleTime: 30_000,
    refetchOnWindowFocus: false,
  });
}

export function useAiStatus(enabled: boolean) {
  return useQuery({
    queryKey: AI_STATUS_QUERY_KEY,
    queryFn: async () => toStatus(await protoFetch(AiStatusResponseSchema, AI_STATUS_PATH)),
    enabled,
    staleTime: 60_000,
  });
}

function useAiSettingsMutation<Input>(send: (input: Input) => Promise<AiSettingsResponse>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: Input) => toSettings(await send(input)),
    onSuccess: (state) => {
      queryClient.setQueryData(AI_SETTINGS_QUERY_KEY, state);
      void queryClient.invalidateQueries({ queryKey: AI_STATUS_QUERY_KEY });
    },
    onError: (error) => {
      if (classifyAiSaveFailure(error) === 'managedByConfig' || isConflictError(error)) {
        void queryClient.invalidateQueries({ queryKey: AI_SETTINGS_QUERY_KEY });
      }
    },
  });
}

export function useSaveAiConnection() {
  return useAiSettingsMutation(async ({ provider, baseUrl, apiKey, model, activate }: AiConnectionSave) => protoSend(
    'PUT',
    `${AI_SETTINGS_PATH}/connections/${encodeURIComponent(provider)}`,
    SaveAiConnectionRequestSchema,
    create(SaveAiConnectionRequestSchema, {
      activate,
      ...optionalField('baseUrl', baseUrl),
      ...optionalField('apiKey', apiKey),
      ...optionalField('model', model),
    }),
    AiSettingsResponseSchema,
  ));
}

export function useRemoveAiConnection() {
  return useAiSettingsMutation(async (provider: string) => protoFetch(
    AiSettingsResponseSchema,
    `${AI_SETTINGS_PATH}/connections/${encodeURIComponent(provider)}`,
    { method: 'DELETE' },
  ));
}

export function useUpdateAiSettings() {
  return useAiSettingsMutation(async ({ activeProvider, limitsWrite }: AiSettingsUpdate) => protoSend(
    'PUT',
    AI_SETTINGS_PATH,
    UpdateAiSettingsRequestSchema,
    create(UpdateAiSettingsRequestSchema, {
      ...optionalField('activeProvider', activeProvider),
      ...optionalField('limitsUpdate', limitsWrite && { expectedVersion: BigInt(limitsWrite.expectedVersion), limits: limitsWrite.limits }),
    }),
    AiSettingsResponseSchema,
  ));
}

export function useAiModels() {
  return useMutation({
    mutationFn: async ({ draft, refresh }: { draft: AiDraftInput; refresh: boolean }): Promise<AiModelList> => toModelList(await protoSend(
      'POST',
      `${AI_SETTINGS_PATH}/models`,
      ListAiModelsRequestSchema,
      create(ListAiModelsRequestSchema, { connection: draftMessage(draft), refresh }),
      AiModelsResponseSchema,
    )),
  });
}

export function useTestAiConnection() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (draft: AiDraftInput): Promise<AiTestResult> => toTestResult(await protoSend(
      'POST',
      `${AI_SETTINGS_PATH}/test`,
      TestAiConnectionRequestSchema,
      create(TestAiConnectionRequestSchema, { connection: draftMessage(draft) }),
      AiTestResponseSchema,
    )),
    onSuccess: (result) => {
      if (result.success) {
        void queryClient.invalidateQueries({ queryKey: AI_SETTINGS_QUERY_KEY });
      }
    },
  });
}
