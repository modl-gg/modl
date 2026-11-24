import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { create } from '@bufbuild/protobuf';
import {
  DatabaseKind,
  DatabaseMovePhase,
  DatabaseRestartMode,
  DatabaseSettingsResponseSchema,
  DatabaseWarningKind,
  MoveDatabaseRequestSchema,
  TestDatabaseConnectionRequestSchema,
  TestDatabaseConnectionResponseSchema,
  type DatabaseConnectionView,
  type DatabaseMoveView,
  type DatabaseSettingsResponse,
  type DatabaseWarning as DatabaseWarningMessage,
} from '@proto/modl/v1/operations_pb.ts';
import { isConflictError } from '@/lib/http-error';
import { protoFetch, protoSend } from '@/lib/proto-fetch';
import { tsToIso } from '@/lib/proto-ui';
import type { QueryGate } from './query-gate';

const DATABASE_PATH = '/v1/panel/settings/database';
const DATABASE_QUERY_KEY = [DATABASE_PATH] as const;
const MOVE_REFRESH_MS = 2_000;

export type DatabaseKindId = 'sqlite' | 'mysql' | 'mariadb' | 'postgresql';

export type DatabaseMovePhaseId =
  | 'checking'
  | 'pausing-writes'
  | 'copying'
  | 'verifying'
  | 'restarting'
  | 'waiting-for-restart'
  | 'failed';

export type DatabaseRestartModeId = 'in-place' | 'exit' | 'manual';

export type DatabaseWarningKindId = 'setting' | 'not-strict' | 'journal-mode' | 'synchronous' | 'not-encrypted' | 'no-nightly-backups';

export interface DatabaseWarning {
  kind: DatabaseWarningKindId;
  setting: string;
  value: string;
  expected: string;
  host: string;
}

export interface DatabaseConnection {
  kind: DatabaseKindId;
  host: string;
  port: number;
  database: string;
  username: string;
  tls: boolean;
}

export interface DatabaseConnectionDraft extends DatabaseConnection {
  password: string;
}

export interface DatabaseMoveInput {
  draft: DatabaseConnectionDraft;
  manualRestartAcknowledged: boolean;
}

export interface DatabaseMove {
  phase: DatabaseMovePhaseId;
  target: DatabaseConnection;
  errorCode: string;
  error: string;
  errorDetail: string;
  copiedTables: number;
  copiedRows: number;
  startedAt: string | null;
  updatedAt: string | null;
}

export interface DatabaseSettings {
  current: DatabaseConnection;
  managed: boolean;
  restartMode: DatabaseRestartModeId;
  move: DatabaseMove | null;
}

export interface DatabaseTestResult {
  ok: boolean;
  code: string;
  message: string;
  detail: string;
  serverVersion: string;
  warnings: DatabaseWarning[];
  replacesCopy: boolean;
}

export function isMoveActive(move: DatabaseMove | null): boolean {
  return move !== null && move.phase !== 'failed';
}

export function useDatabaseSettings({ enabled = true }: QueryGate = {}) {
  return useQuery<DatabaseSettings>({
    queryKey: DATABASE_QUERY_KEY,
    enabled,
    queryFn: async () => toDatabaseSettings(await protoFetch(DatabaseSettingsResponseSchema, DATABASE_PATH)),
    refetchInterval: (query) => (isMoveActive(query.state.data?.move ?? null) ? MOVE_REFRESH_MS : false),
    refetchIntervalInBackground: true,
    refetchOnWindowFocus: false,
  });
}

export function useTestDatabaseConnection() {
  return useMutation({
    mutationFn: async (draft: DatabaseConnectionDraft): Promise<DatabaseTestResult> => {
      const result = await protoSend('POST', `${DATABASE_PATH}/test`, TestDatabaseConnectionRequestSchema,
        create(TestDatabaseConnectionRequestSchema, { connection: toInput(draft) }), TestDatabaseConnectionResponseSchema);
      return {
        ok: result.ok,
        code: result.code,
        message: result.message,
        detail: result.detail,
        serverVersion: result.serverVersion,
        warnings: result.warnings.map(toWarning),
        replacesCopy: result.replacesCopy,
      };
    },
  });
}

export function useMoveDatabase() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ draft, manualRestartAcknowledged }: DatabaseMoveInput) => toDatabaseSettings(await protoSend('POST',
      `${DATABASE_PATH}/move`, MoveDatabaseRequestSchema,
      create(MoveDatabaseRequestSchema, { connection: toInput(draft), manualRestartAcknowledged }), DatabaseSettingsResponseSchema)),
    onSuccess: (settings) => {
      queryClient.setQueryData(DATABASE_QUERY_KEY, settings);
    },
    onError: (error) => {
      if (isConflictError(error)) void queryClient.invalidateQueries({ queryKey: DATABASE_QUERY_KEY });
    },
  });
}

function toInput(draft: DatabaseConnectionDraft) {
  if (draft.kind === 'sqlite') return { kind: DatabaseKind.SQLITE };
  return {
    kind: toKind(draft.kind),
    host: draft.host.trim(),
    port: draft.port,
    database: draft.database.trim(),
    username: draft.username.trim(),
    password: draft.password,
    tls: draft.tls,
  };
}

function toDatabaseSettings(response: DatabaseSettingsResponse): DatabaseSettings {
  return {
    current: toConnection(response.current),
    managed: response.managed,
    restartMode: toRestartModeId(response.restartMode),
    move: response.move ? toMove(response.move) : null,
  };
}

function toConnection(view: DatabaseConnectionView | undefined): DatabaseConnection {
  return {
    kind: toKindId(view?.kind ?? DatabaseKind.SQLITE),
    host: view?.host ?? '',
    port: view?.port ?? 0,
    database: view?.database ?? '',
    username: view?.username ?? '',
    tls: view?.tls ?? false,
  };
}

function toMove(move: DatabaseMoveView): DatabaseMove {
  return {
    phase: toPhaseId(move.phase),
    target: toConnection(move.target),
    errorCode: move.errorCode,
    error: move.error,
    errorDetail: move.errorDetail,
    copiedTables: Number(move.copiedTables),
    copiedRows: Number(move.copiedRows),
    startedAt: tsToIso(move.startedAt),
    updatedAt: tsToIso(move.updatedAt),
  };
}

function toWarning(warning: DatabaseWarningMessage): DatabaseWarning {
  return {
    kind: toWarningKindId(warning.kind),
    setting: warning.setting,
    value: warning.value,
    expected: warning.expected,
    host: warning.host,
  };
}

function toWarningKindId(kind: DatabaseWarningKind): DatabaseWarningKindId {
  switch (kind) {
    case DatabaseWarningKind.NOT_STRICT:
      return 'not-strict';
    case DatabaseWarningKind.JOURNAL_MODE:
      return 'journal-mode';
    case DatabaseWarningKind.SYNCHRONOUS:
      return 'synchronous';
    case DatabaseWarningKind.NOT_ENCRYPTED:
      return 'not-encrypted';
    case DatabaseWarningKind.NO_NIGHTLY_BACKUPS:
      return 'no-nightly-backups';
    case DatabaseWarningKind.SETTING:
    case DatabaseWarningKind.UNSPECIFIED:
      return 'setting';
  }
}

function toRestartModeId(mode: DatabaseRestartMode): DatabaseRestartModeId {
  switch (mode) {
    case DatabaseRestartMode.IN_PLACE:
      return 'in-place';
    case DatabaseRestartMode.EXIT:
      return 'exit';
    case DatabaseRestartMode.MANUAL:
    case DatabaseRestartMode.UNSPECIFIED:
      return 'manual';
  }
}

const KINDS = {
  sqlite: DatabaseKind.SQLITE,
  mysql: DatabaseKind.MYSQL,
  mariadb: DatabaseKind.MARIADB,
  postgresql: DatabaseKind.POSTGRESQL,
} as const satisfies Record<DatabaseKindId, DatabaseKind>;

function toKind(id: DatabaseKindId): DatabaseKind {
  return KINDS[id];
}

function toKindId(kind: DatabaseKind): DatabaseKindId {
  switch (kind) {
    case DatabaseKind.MYSQL:
      return 'mysql';
    case DatabaseKind.MARIADB:
      return 'mariadb';
    case DatabaseKind.POSTGRESQL:
      return 'postgresql';
    case DatabaseKind.SQLITE:
    case DatabaseKind.UNSPECIFIED:
      return 'sqlite';
  }
}

function toPhaseId(phase: DatabaseMovePhase): DatabaseMovePhaseId {
  switch (phase) {
    case DatabaseMovePhase.CHECKING:
      return 'checking';
    case DatabaseMovePhase.PAUSING_WRITES:
      return 'pausing-writes';
    case DatabaseMovePhase.COPYING:
      return 'copying';
    case DatabaseMovePhase.VERIFYING:
      return 'verifying';
    case DatabaseMovePhase.RESTARTING:
      return 'restarting';
    case DatabaseMovePhase.WAITING_FOR_RESTART:
      return 'waiting-for-restart';
    case DatabaseMovePhase.FAILED:
    case DatabaseMovePhase.UNSPECIFIED:
      return 'failed';
  }
}
