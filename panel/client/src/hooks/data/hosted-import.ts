import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { create } from '@bufbuild/protobuf';
import { timestampDate, type Timestamp } from '@bufbuild/protobuf/wkt';
import {
  BeginHostedImportUploadRequestSchema,
  HostedImportActionRequestSchema,
  HostedImportPreviewSchema,
  HostedImportReportSchema,
  HostedImportRetention,
  HostedImportSeverity,
  HostedImportStaffPageSchema,
  HostedImportStateSchema,
  HostedImportStatus,
  HostedImportPhase,
  StartHostedImportDownloadRequestSchema,
  StartHostedImportRequestSchema,
  type HostedImportCount,
  type HostedImportIssue,
  type HostedImportRetentionPlan,
  type HostedImportState,
} from '@proto/modl/v1/hosted_import_pb.ts';
import { protoFetch, protoSend } from '@/lib/proto-fetch';
import { optionalField } from '@/lib/defined-fields';

export const IMPORT_PATH = '/v1/panel/setup/import';
const IMPORT_QUERY_KEY = [IMPORT_PATH] as const;
const PREVIEW_QUERY_KEY = [`${IMPORT_PATH}/preview`] as const;
const REPORT_QUERY_KEY = [`${IMPORT_PATH}/report`] as const;
const STAFF_QUERY_KEY = `${IMPORT_PATH}/staff`;
const ACTIVE_POLL_MS = 2_000;

export type ImportStatusId =
  | 'none' | 'receiving' | 'downloading' | 'verifying' | 'review' | 'applying' | 'paused' | 'completed' | 'abandoned' | 'failed';
export type ImportPhaseId =
  | 'verify' | 'begin' | 'configuration' | 'files' | 'players' | 'links' | 'tickets' | 'replays'
  | 'chatLogs' | 'commandLogs' | 'auditLogs' | 'access' | 'subscriptions' | 'finish';
export type RetentionChoiceId = 'keepHistory' | 'standard';
export type IssueSeverityId = 'info' | 'changed' | 'lost' | 'action';

const STATUSES: ReadonlyMap<HostedImportStatus, ImportStatusId> = new Map<HostedImportStatus, ImportStatusId>([
  [HostedImportStatus.UNSPECIFIED, 'none'],
  [HostedImportStatus.NONE, 'none'],
  [HostedImportStatus.RECEIVING, 'receiving'],
  [HostedImportStatus.DOWNLOADING, 'downloading'],
  [HostedImportStatus.VERIFYING, 'verifying'],
  [HostedImportStatus.REVIEW, 'review'],
  [HostedImportStatus.APPLYING, 'applying'],
  [HostedImportStatus.PAUSED, 'paused'],
  [HostedImportStatus.COMPLETED, 'completed'],
  [HostedImportStatus.ABANDONED, 'abandoned'],
  [HostedImportStatus.FAILED, 'failed'],
]);

const PHASES: ReadonlyMap<HostedImportPhase, ImportPhaseId> = new Map<HostedImportPhase, ImportPhaseId>([
  [HostedImportPhase.UNSPECIFIED, 'verify'],
  [HostedImportPhase.VERIFY, 'verify'],
  [HostedImportPhase.BEGIN, 'begin'],
  [HostedImportPhase.CONFIGURATION, 'configuration'],
  [HostedImportPhase.FILES, 'files'],
  [HostedImportPhase.PLAYERS, 'players'],
  [HostedImportPhase.LINKS, 'links'],
  [HostedImportPhase.TICKETS, 'tickets'],
  [HostedImportPhase.REPLAYS, 'replays'],
  [HostedImportPhase.CHAT_LOGS, 'chatLogs'],
  [HostedImportPhase.COMMAND_LOGS, 'commandLogs'],
  [HostedImportPhase.AUDIT_LOGS, 'auditLogs'],
  [HostedImportPhase.ACCESS, 'access'],
  [HostedImportPhase.SUBSCRIPTIONS, 'subscriptions'],
  [HostedImportPhase.FINISH, 'finish'],
]);

const SEVERITIES: ReadonlyMap<HostedImportSeverity, IssueSeverityId> = new Map<HostedImportSeverity, IssueSeverityId>([
  [HostedImportSeverity.UNSPECIFIED, 'info'],
  [HostedImportSeverity.INFO, 'info'],
  [HostedImportSeverity.CHANGED, 'changed'],
  [HostedImportSeverity.LOST, 'lost'],
  [HostedImportSeverity.ACTION, 'action'],
]);

const RETENTION_CHOICES: Readonly<Record<RetentionChoiceId, HostedImportRetention>> = {
  keepHistory: HostedImportRetention.KEEP_HISTORY,
  standard: HostedImportRetention.STANDARD,
};

const IN_PROGRESS: ReadonlySet<ImportStatusId> = new Set<ImportStatusId>(['receiving', 'downloading', 'verifying', 'review', 'applying', 'paused']);
const POLLED: ReadonlySet<ImportStatusId> = new Set<ImportStatusId>(['downloading', 'verifying', 'applying']);

export interface ImportState {
  status: ImportStatusId;
  importId: string;
  eligible: boolean;
  blockers: string[];
  phase: ImportPhaseId;
  receivedBytes: number;
  totalBytes: number;
  phaseDone: number;
  phaseTotal: number;
  reasonCode: string;
  requiredBytes: number;
  archiveSha256: string;
  canDiscard: boolean;
  canResume: boolean;
  canAbandon: boolean;
  canFinish: boolean;
  maxChunkBytes: number;
  fileName: string;
}

export interface ImportCount {
  subject: string;
  total: number;
  imported: number;
  changed: number;
  skipped: number;
}

export interface ImportIssue {
  code: string;
  severity: IssueSeverityId;
  count: number;
  samples: string[];
}

export interface RetentionOption {
  choice: RetentionChoiceId;
  chatLogDays: number;
  auditLogDays: number;
  replayRetentionEnabled: boolean;
  replayDays: number;
  rowsNotKept: number;
  replaysNotKept: number;
  keepsIpHistory: boolean;
  requiredBytes: number;
}

export interface ImportPreviewView {
  importId: string;
  serverName: string;
  subdomain: string;
  exportedAt: string | null;
  archivedOwnerEmail: string;
  localOwnerEmail: string;
  ownerEmailsMatch: boolean;
  archivedOwnerHasStaffRecord: boolean;
  archivedOwnerMinecraftUsername: string;
  counts: ImportCount[];
  issues: ImportIssue[];
  retention: RetentionOption[];
  availableBytes: number;
  staffTotal: number;
}

export interface StaffCandidate {
  sourceId: string;
  email: string;
  username: string;
  roleName: string;
  permissions: string[];
  minecraftUsername: string;
  selectable: boolean;
  blockedCode: string;
}

export interface ImportReportView {
  status: ImportStatusId;
  exportId: string;
  exportedAt: string | null;
  finishedAt: string | null;
  counts: ImportCount[];
  issues: ImportIssue[];
  excluded: { name: string; reason: string; count: number }[];
  skippedObjects: { reason: string; count: number }[];
  followUps: string[];
  retention: RetentionChoiceId | null;
}

export interface StartImportInput {
  importId: string;
  staffIds: string[];
  mergeArchivedOwner: boolean;
  retention: RetentionChoiceId;
}

function isoOf(timestamp: Timestamp | undefined): string | null {
  return timestamp ? timestampDate(timestamp).toISOString() : null;
}

function toState(state: HostedImportState): ImportState {
  return {
    status: STATUSES.get(state.status) ?? 'none',
    importId: state.importId,
    eligible: state.eligible,
    blockers: [...state.blockers],
    phase: PHASES.get(state.phase) ?? 'verify',
    receivedBytes: Number(state.receivedBytes),
    totalBytes: Number(state.totalBytes),
    phaseDone: Number(state.phaseDone),
    phaseTotal: Number(state.phaseTotal),
    reasonCode: state.reasonCode,
    requiredBytes: Number(state.requiredBytes),
    archiveSha256: state.archiveSha256,
    canDiscard: state.canDiscard,
    canResume: state.canResume,
    canAbandon: state.canAbandon,
    canFinish: state.canFinish,
    maxChunkBytes: state.maxChunkBytes,
    fileName: state.fileName,
  };
}

function toCount(count: HostedImportCount): ImportCount {
  return {
    subject: count.subject,
    total: Number(count.total),
    imported: Number(count.imported),
    changed: Number(count.changed),
    skipped: Number(count.skipped),
  };
}

function toIssue(issue: HostedImportIssue): ImportIssue {
  return { code: issue.code, severity: SEVERITIES.get(issue.severity) ?? 'info', count: Number(issue.count), samples: [...issue.samples] };
}

function toRetention(plan: HostedImportRetentionPlan): RetentionOption | null {
  const choice = plan.option === HostedImportRetention.KEEP_HISTORY ? 'keepHistory'
    : plan.option === HostedImportRetention.STANDARD ? 'standard' : null;
  if (choice === null) return null;
  return {
    choice,
    chatLogDays: plan.chatLogDays,
    auditLogDays: plan.auditLogDays,
    replayRetentionEnabled: plan.replayRetentionEnabled,
    replayDays: plan.replayDays,
    rowsNotKept: Number(plan.rowsNotKept),
    replaysNotKept: Number(plan.replaysNotKept),
    keepsIpHistory: plan.keepsIpHistory,
    requiredBytes: Number(plan.requiredBytes),
  };
}

export function isImportInProgress(state: ImportState | undefined): boolean {
  return state !== undefined && IN_PROGRESS.has(state.status);
}

export function useHostedImport({ enabled = true }: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: IMPORT_QUERY_KEY,
    enabled,
    queryFn: async () => toState(await protoFetch(HostedImportStateSchema, IMPORT_PATH)),
    refetchInterval: (query) => (query.state.data && POLLED.has(query.state.data.status) ? ACTIVE_POLL_MS : false),
    refetchIntervalInBackground: false,
    retry: 1,
  });
}

export function useHostedImportPreview(enabled: boolean) {
  return useQuery({
    queryKey: PREVIEW_QUERY_KEY,
    enabled,
    queryFn: async (): Promise<ImportPreviewView> => {
      const preview = await protoFetch(HostedImportPreviewSchema, `${IMPORT_PATH}/preview`);
      return {
        importId: preview.importId,
        serverName: preview.serverName,
        subdomain: preview.subdomain,
        exportedAt: isoOf(preview.exportedAt),
        archivedOwnerEmail: preview.archivedOwnerEmail,
        localOwnerEmail: preview.localOwnerEmail,
        ownerEmailsMatch: preview.ownerEmailsMatch,
        archivedOwnerHasStaffRecord: preview.archivedOwnerHasStaffRecord,
        archivedOwnerMinecraftUsername: preview.archivedOwnerMinecraftUsername,
        counts: preview.counts.map(toCount),
        issues: preview.issues.map(toIssue),
        retention: preview.retention.map(toRetention).filter((option): option is RetentionOption => option !== null),
        availableBytes: Number(preview.availableBytes),
        staffTotal: preview.staffTotal,
      };
    },
    staleTime: 60_000,
    retry: 1,
  });
}

export function useHostedImportStaff(enabled: boolean, cursor: string) {
  return useQuery({
    queryKey: [STAFF_QUERY_KEY, cursor],
    enabled,
    queryFn: async () => {
      const page = await protoFetch(HostedImportStaffPageSchema, `${STAFF_QUERY_KEY}?cursor=${encodeURIComponent(cursor)}`);
      return {
        staff: page.staff.map((candidate): StaffCandidate => ({
          sourceId: candidate.sourceId,
          email: candidate.email,
          username: candidate.username,
          roleName: candidate.roleName,
          permissions: [...candidate.permissions],
          minecraftUsername: candidate.minecraftUsername,
          selectable: candidate.selectable,
          blockedCode: candidate.blockedCode,
        })),
        nextCursor: page.nextCursor,
      };
    },
    staleTime: 60_000,
  });
}

export function useHostedImportReport(enabled: boolean) {
  return useQuery({
    queryKey: REPORT_QUERY_KEY,
    enabled,
    queryFn: async (): Promise<ImportReportView> => {
      const report = await protoFetch(HostedImportReportSchema, `${IMPORT_PATH}/report`);
      return {
        status: STATUSES.get(report.status) ?? 'none',
        exportId: report.exportId,
        exportedAt: isoOf(report.exportedAt),
        finishedAt: isoOf(report.finishedAt),
        counts: report.counts.map(toCount),
        issues: report.issues.map(toIssue),
        excluded: report.excludedCollections.map((value) => ({ name: value.name, reason: value.reason, count: Number(value.approximateCount) })),
        skippedObjects: report.skippedObjects.map((value) => ({ reason: value.reason, count: Number(value.approximateCount) })),
        followUps: [...report.followUpCodes],
        retention: report.retention === HostedImportRetention.KEEP_HISTORY ? 'keepHistory'
          : report.retention === HostedImportRetention.STANDARD ? 'standard' : null,
      };
    },
    retry: 1,
  });
}

function useImportMutation<Input>(send: (input: Input) => Promise<HostedImportState>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: Input) => toState(await send(input)),
    onSuccess: (state) => {
      queryClient.setQueryData(IMPORT_QUERY_KEY, state);
      void queryClient.invalidateQueries({ queryKey: PREVIEW_QUERY_KEY });
    },
  });
}

const action = (path: string) => async (importId: string) => protoSend('POST', `${IMPORT_PATH}${path}`,
  HostedImportActionRequestSchema, create(HostedImportActionRequestSchema, { importId }), HostedImportStateSchema);

export function useBeginImportUpload() {
  return useImportMutation(async ({ file, expectedSha256 }: { file: File; expectedSha256: string }) => protoSend('POST',
    `${IMPORT_PATH}/upload`, BeginHostedImportUploadRequestSchema, create(BeginHostedImportUploadRequestSchema, {
      sizeBytes: BigInt(file.size),
      fileName: file.name.slice(0, 255),
      ...optionalField('expectedSha256', expectedSha256 === '' ? undefined : expectedSha256),
    }), HostedImportStateSchema));
}

export function useStartImportDownload() {
  return useImportMutation(async ({ url, expectedSha256 }: { url: string; expectedSha256: string }) => protoSend('POST',
    `${IMPORT_PATH}/download`, StartHostedImportDownloadRequestSchema, create(StartHostedImportDownloadRequestSchema, {
      url,
      ...optionalField('expectedSha256', expectedSha256 === '' ? undefined : expectedSha256),
    }), HostedImportStateSchema));
}

export function useCompleteImportUpload() {
  return useImportMutation(action('/upload/complete'));
}

export function useResumeImport() {
  return useImportMutation(action('/resume'));
}

export function useDiscardImport() {
  return useImportMutation(action('/discard'));
}

export function useAbandonImport() {
  return useImportMutation(action('/abandon'));
}

export function useFinishImport() {
  return useImportMutation(action('/finish'));
}

export function useStartImport() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: StartImportInput) => toState(await protoSend('POST', `${IMPORT_PATH}/start`,
      StartHostedImportRequestSchema, create(StartHostedImportRequestSchema, {
        importId: input.importId,
        staffIds: input.staffIds,
        mergeArchivedOwner: input.mergeArchivedOwner,
        retention: RETENTION_CHOICES[input.retention],
        confirmed: true,
      }), HostedImportStateSchema)),
    onSuccess: (state) => queryClient.setQueryData(IMPORT_QUERY_KEY, state),
  });
}

export function useImportUploadProgress() {
  const queryClient = useQueryClient();
  return (state: HostedImportState) => queryClient.setQueryData(IMPORT_QUERY_KEY, toState(state));
}

export function useInvalidateAfterImport() {
  const queryClient = useQueryClient();
  return () => {
    void queryClient.invalidateQueries();
  };
}
