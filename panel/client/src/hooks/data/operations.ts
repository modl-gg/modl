import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { create } from '@bufbuild/protobuf';
import {
  OperatorLimitKey,
  OperatorLimitsResponseSchema,
  PatchOperatorLimitsRequestSchema,
  SaveS3ConnectionRequestSchema,
  StatusLevel,
  StatusReportResponseSchema,
  StorageBackendKind,
  StorageSettingsResponseSchema,
  StorageTransferState,
  StorageUsageGroupKind,
  SwitchStorageBackendRequestSchema,
  TestS3ConnectionRequestSchema,
  TestS3ConnectionResponseSchema,
  type OperatorLimit,
  type OperatorLimitsResponse,
  type StorageSettingsResponse,
  type StorageTransferView,
} from '@proto/modl/v1/operations_pb.ts';
import { isConflictError } from '@/lib/http-error';
import { protoFetch, protoSend } from '@/lib/proto-fetch';
import { tsToIso } from '@/lib/proto-ui';
import type { QueryGate } from './query-gate';

const STORAGE_PATH = '/v1/panel/settings/storage';
const STATUS_PATH = '/v1/panel/settings/status';
const RETENTION_LIMITS_PATH = '/v1/panel/settings/data-retention/limits';
const STORAGE_QUERY_KEY = [STORAGE_PATH] as const;
const RETENTION_LIMITS_QUERY_KEY = [RETENTION_LIMITS_PATH] as const;
const TRANSFER_REFRESH_MS = 3_000;

export type OperatorLimitId =
  | 'maxUploadMb'
  | 'maxStorageGb'
  | 'backupKeep'
  | 'backupHour'
  | 'ipHistoryDays'
  | 'notificationDays';

export interface OperatorLimitSetting {
  id: OperatorLimitId;
  value: number;
  min: number;
  max: number;
  defaultValue: number;
  managed: boolean;
  environmentVariable: string;
}

export type StorageBackendId = 'local' | 's3';
export type StorageTransferStateId = 'running' | 'failed' | 'done';

export interface S3Connection {
  endpoint: string;
  region: string;
  bucket: string;
  accessKey: string;
  hasSecretKey: boolean;
  publicUrl: string;
}

export interface S3ConnectionDraft {
  endpoint: string;
  region: string;
  bucket: string;
  accessKey: string;
  secretKey: string;
  publicUrl: string;
}

export interface StorageTransfer {
  state: StorageTransferStateId;
  source: StorageBackendId;
  target: StorageBackendId;
  copiedObjects: number;
  copiedBytes: number;
  skippedObjects: number;
  failedObjects: number;
  lastError: string;
  startedAt: string | null;
  updatedAt: string | null;
  finishedAt: string | null;
}

export interface StorageSettings {
  limits: OperatorLimitSetting[];
  limitsVersion: number;
  active: StorageBackendId;
  managed: boolean;
  s3: S3Connection;
  s3Configured: boolean;
  transfer: StorageTransfer | null;
  oldCopies: StorageBackendId[];
  removingOldCopies: boolean;
  usedBytes: number;
  capacityBytes: number;
  usage: StorageUsageGroup[];
}

export type StorageUsageGroupId = 'replays' | 'evidence' | 'tickets' | 'articles' | 'icons' | 'other';

export interface StorageUsageGroup {
  group: StorageUsageGroupId;
  files: number;
  bytes: number;
}

const USAGE_GROUP_IDS: ReadonlyMap<StorageUsageGroupKind, StorageUsageGroupId> = new Map([
  [StorageUsageGroupKind.REPLAYS, 'replays'],
  [StorageUsageGroupKind.EVIDENCE, 'evidence'],
  [StorageUsageGroupKind.TICKETS, 'tickets'],
  [StorageUsageGroupKind.ARTICLES, 'articles'],
  [StorageUsageGroupKind.ICONS, 'icons'],
  [StorageUsageGroupKind.OTHER, 'other'],
]);

export interface VersionedLimits {
  limits: OperatorLimitSetting[];
  version: number;
}

export interface S3TestResult {
  ok: boolean;
  code: string;
  message: string;
}

export type StatusLevelId = 'ok' | 'warning' | 'problem';

export interface StatusReportEntry {
  key: string;
  section: string;
  label: string;
  value: string;
  level: StatusLevelId;
}

export interface StatusReport {
  entries: StatusReportEntry[];
  report: string;
  generatedAt: string | null;
}

export function useStorageSettings({ enabled = true }: QueryGate = {}) {
  return useQuery<StorageSettings>({
    queryKey: STORAGE_QUERY_KEY,
    enabled,
    queryFn: async () => toStorageSettings(await protoFetch(StorageSettingsResponseSchema, STORAGE_PATH)),
    refetchInterval: (query) => (isBusy(query.state.data) ? TRANSFER_REFRESH_MS : false),
    refetchOnWindowFocus: false,
  });
}

function isBusy(settings: StorageSettings | undefined): boolean {
  return settings?.transfer?.state === 'running' || settings?.removingOldCopies === true;
}

function useStorageMutation<Input>(send: (input: Input) => Promise<StorageSettingsResponse>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: Input) => toStorageSettings(await send(input)),
    onSuccess: (settings) => {
      queryClient.setQueryData(STORAGE_QUERY_KEY, settings);
    },
    onError: (error) => {
      if (isConflictError(error)) void queryClient.invalidateQueries({ queryKey: STORAGE_QUERY_KEY });
    },
  });
}

interface LimitChanges {
  expectedVersion: number;
  values: Partial<Record<OperatorLimitId, number>>;
}

function toPatchRequest({ expectedVersion, values }: LimitChanges) {
  return create(PatchOperatorLimitsRequestSchema, {
    expectedVersion: BigInt(expectedVersion),
    limits: Object.entries(values).flatMap(([id, value]) =>
      isLimitId(id) ? [{ key: toLimitKey(id), value }] : []),
  });
}

export function usePatchOperatorLimits() {
  return useStorageMutation(async (changes: LimitChanges) => protoSend('PATCH', `${STORAGE_PATH}/limits`,
    PatchOperatorLimitsRequestSchema, toPatchRequest(changes), StorageSettingsResponseSchema));
}

export function useRetentionLimits({ enabled = true }: QueryGate = {}) {
  return useQuery<VersionedLimits>({
    queryKey: RETENTION_LIMITS_QUERY_KEY,
    enabled,
    queryFn: async () => toVersionedLimits(await protoFetch(OperatorLimitsResponseSchema, RETENTION_LIMITS_PATH)),
    refetchOnWindowFocus: false,
  });
}

export function usePatchRetentionLimits() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (changes: LimitChanges) => toVersionedLimits(await protoSend('PATCH', RETENTION_LIMITS_PATH,
      PatchOperatorLimitsRequestSchema, toPatchRequest(changes), OperatorLimitsResponseSchema)),
    onSuccess: (limits) => {
      queryClient.setQueryData(RETENTION_LIMITS_QUERY_KEY, limits);
    },
  });
}

export function useRemoveOldCopies() {
  return useStorageMutation<undefined>(async () =>
    protoFetch(StorageSettingsResponseSchema, `${STORAGE_PATH}/old-copies/remove`, { method: 'POST' }));
}

export function useSaveS3Connection() {
  return useStorageMutation(async (draft: S3ConnectionDraft) => protoSend('PUT', `${STORAGE_PATH}/s3`,
    SaveS3ConnectionRequestSchema, create(SaveS3ConnectionRequestSchema, { connection: toInput(draft) }),
    StorageSettingsResponseSchema));
}

export function useSwitchStorageBackend() {
  return useStorageMutation(async (target: StorageBackendId) => protoSend('POST', `${STORAGE_PATH}/switch`,
    SwitchStorageBackendRequestSchema,
    create(SwitchStorageBackendRequestSchema, { target: target === 's3' ? StorageBackendKind.S3 : StorageBackendKind.LOCAL }),
    StorageSettingsResponseSchema));
}

export function useRetryStorageTransfer() {
  return useStorageMutation<undefined>(async () =>
    protoFetch(StorageSettingsResponseSchema, `${STORAGE_PATH}/transfer/retry`, { method: 'POST' }));
}

export function useTestS3Connection() {
  return useMutation({
    mutationFn: async (draft: S3ConnectionDraft): Promise<S3TestResult> => {
      const result = await protoSend('POST', `${STORAGE_PATH}/s3/test`, TestS3ConnectionRequestSchema,
        create(TestS3ConnectionRequestSchema, { connection: toInput(draft) }), TestS3ConnectionResponseSchema);
      return { ok: result.ok, code: result.code, message: result.message };
    },
  });
}

export function useStatusReport({ enabled = true }: QueryGate = {}) {
  return useQuery<StatusReport>({
    queryKey: [STATUS_PATH],
    enabled,
    queryFn: async () => {
      const response = await protoFetch(StatusReportResponseSchema, STATUS_PATH);
      return {
        entries: response.entries.map((entry) => ({
          key: entry.key,
          section: entry.section,
          label: entry.label,
          value: entry.value,
          level: toLevel(entry.level),
        })),
        report: response.report,
        generatedAt: tsToIso(response.generatedAt),
      };
    },
    refetchOnWindowFocus: false,
  });
}

function toInput(draft: S3ConnectionDraft) {
  const secretKey = draft.secretKey.trim();
  return {
    endpoint: draft.endpoint.trim(),
    region: draft.region.trim(),
    bucket: draft.bucket.trim(),
    accessKey: draft.accessKey.trim(),
    publicUrl: draft.publicUrl.trim(),
    ...(secretKey === '' ? {} : { secretKey }),
  };
}

function toStorageSettings(response: StorageSettingsResponse): StorageSettings {
  const backend = response.backend;
  const s3 = backend?.s3;
  return {
    limits: response.limits.flatMap(toLimit),
    limitsVersion: Number(response.limitsVersion),
    active: toBackendId(backend?.active ?? StorageBackendKind.LOCAL),
    managed: backend?.managed ?? false,
    s3: {
      endpoint: s3?.endpoint ?? '',
      region: s3?.region ?? '',
      bucket: s3?.bucket ?? '',
      accessKey: s3?.accessKey ?? '',
      hasSecretKey: s3?.hasSecretKey ?? false,
      publicUrl: s3?.publicUrl ?? '',
    },
    s3Configured: backend?.s3Configured ?? false,
    transfer: response.transfer ? toTransfer(response.transfer) : null,
    oldCopies: (backend?.oldCopies ?? []).map(toBackendId),
    removingOldCopies: backend?.removingOldCopies ?? false,
    usedBytes: Number(response.usedBytes),
    capacityBytes: Number(response.capacityBytes),
    usage: response.usage.flatMap((entry) => {
      const group = USAGE_GROUP_IDS.get(entry.group);
      return group === undefined ? [] : [{ group, files: Number(entry.files), bytes: Number(entry.bytes) }];
    }),
  };
}

function toBackendId(kind: StorageBackendKind): StorageBackendId {
  return kind === StorageBackendKind.S3 ? 's3' : 'local';
}

function toVersionedLimits(response: OperatorLimitsResponse): VersionedLimits {
  return { limits: response.limits.flatMap(toLimit), version: Number(response.limitsVersion) };
}

function toTransfer(transfer: StorageTransferView): StorageTransfer {
  return {
    state: toTransferState(transfer.state),
    source: toBackendId(transfer.source),
    target: toBackendId(transfer.target),
    copiedObjects: Number(transfer.copiedObjects),
    copiedBytes: Number(transfer.copiedBytes),
    skippedObjects: Number(transfer.skippedObjects),
    failedObjects: Number(transfer.failedObjects),
    lastError: transfer.lastError,
    startedAt: tsToIso(transfer.startedAt),
    updatedAt: tsToIso(transfer.updatedAt),
    finishedAt: tsToIso(transfer.finishedAt),
  };
}

function toTransferState(state: StorageTransferState): StorageTransferStateId {
  switch (state) {
    case StorageTransferState.RUNNING:
      return 'running';
    case StorageTransferState.DONE:
      return 'done';
    case StorageTransferState.FAILED:
    case StorageTransferState.UNSPECIFIED:
      return 'failed';
  }
}

function toLimit(limit: OperatorLimit): OperatorLimitSetting[] {
  const id = fromLimitKey(limit.key);
  return id === null ? [] : [{
    id,
    value: limit.value,
    min: limit.min,
    max: limit.max,
    defaultValue: limit.defaultValue,
    managed: limit.managed,
    environmentVariable: limit.environmentVariable,
  }];
}

const LIMIT_KEYS = {
  maxUploadMb: OperatorLimitKey.MAX_UPLOAD_MB,
  maxStorageGb: OperatorLimitKey.MAX_STORAGE_GB,
  backupKeep: OperatorLimitKey.BACKUP_KEEP,
  backupHour: OperatorLimitKey.BACKUP_HOUR,
  ipHistoryDays: OperatorLimitKey.IP_HISTORY_DAYS,
  notificationDays: OperatorLimitKey.NOTIFICATION_DAYS,
} as const satisfies Record<OperatorLimitId, OperatorLimitKey>;

function isLimitId(id: string): id is OperatorLimitId {
  return Object.hasOwn(LIMIT_KEYS, id);
}

function toLimitKey(id: OperatorLimitId): OperatorLimitKey {
  return LIMIT_KEYS[id];
}

function fromLimitKey(key: OperatorLimitKey): OperatorLimitId | null {
  switch (key) {
    case OperatorLimitKey.MAX_UPLOAD_MB:
      return 'maxUploadMb';
    case OperatorLimitKey.MAX_STORAGE_GB:
      return 'maxStorageGb';
    case OperatorLimitKey.BACKUP_KEEP:
      return 'backupKeep';
    case OperatorLimitKey.BACKUP_HOUR:
      return 'backupHour';
    case OperatorLimitKey.IP_HISTORY_DAYS:
      return 'ipHistoryDays';
    case OperatorLimitKey.NOTIFICATION_DAYS:
      return 'notificationDays';
    case OperatorLimitKey.UNSPECIFIED:
      return null;
  }
}

function toLevel(level: StatusLevel): StatusLevelId {
  switch (level) {
    case StatusLevel.WARNING:
      return 'warning';
    case StatusLevel.PROBLEM:
      return 'problem';
    case StatusLevel.OK:
    case StatusLevel.UNSPECIFIED:
      return 'ok';
  }
}
