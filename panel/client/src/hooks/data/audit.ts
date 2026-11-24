import { useInfiniteQuery, useMutation, useQuery, type InfiniteData } from '@tanstack/react-query';
import { create } from '@bufbuild/protobuf';
import {
  AuditPunishmentPhase as ProtoAuditPunishmentPhase,
  AuditPunishmentsResponseSchema,
  AuditBulkOperationResponseSchema,
  AuditPunishmentTypesResponseSchema,
  AuditRollbackResponseSchema,
  BulkPunishmentActionRequestSchema,
  RollbackRequestSchema,
  StaffDetailsResponseSchema,
  StaffPerformanceListResponseSchema,
} from '@proto/modl/v1/audit_pb.ts';
import {
  PlayerActivityResponseSchema,
  PunishmentAnalyticsResponseSchema,
  PunishmentIssuerKind as ProtoPunishmentIssuerKind,
  TicketAnalyticsResponseSchema,
} from '@proto/modl/v1/analytics_pb.ts';
import { protoFetch, protoSend } from '@/lib/proto-fetch';
import { queryClient } from '@/lib/queryClient';
import { toNum, tsToDate } from '@/lib/proto-ui';
import { fillDailySeries } from '@/lib/daily-series';
import { AUDIT_PUNISHMENT_TYPES_KEY, AUDIT_QUERY_ROOTS } from './audit-query-keys';
import type { QueryGate } from './query-gate';

const AUDIT_STALE_TIME_MS = 5 * 60 * 1000;

interface DailyCount {
  date: string;
  count: number;
}

const emptyDailyCount = (date: string): DailyCount => ({ date, count: 0 });
const AUDIT_LIST_PAGE_SIZE = 50;
const PUNISHMENT_CHANGE_QUERY_ROOTS = [
  ...AUDIT_QUERY_ROOTS,
  ['/v1/panel/players'],
  ['/v1/panel/players/punishments'],
  ['/v1/panel/dashboard/metrics'],
  ['/v1/panel/dashboard/recent-punishments'],
] as const;

function invalidatePunishmentChange(): void {
  for (const queryKey of PUNISHMENT_CHANGE_QUERY_ROOTS) {
    void queryClient.invalidateQueries({ queryKey });
  }
}

export interface StaffPerformance {
  id: string;
  username: string;
  role: string;
  totalActions: number;
  ticketResponses: number;
  punishmentsIssued: number;
  avgResponseTime: number;
  lastActive: Date | null;
}

export function useStaffPerformance(period: string) {
  return useQuery({
    queryKey: ['staff-performance', period],
    queryFn: async (): Promise<StaffPerformance[]> => {
      const res = await protoFetch(
        StaffPerformanceListResponseSchema,
        `/v1/panel/audit/staff-performance?period=${encodeURIComponent(period)}`,
      );
      return res.staff.map((member) => ({
        id: member.id,
        username: member.username,
        role: member.role,
        totalActions: member.totalActions,
        ticketResponses: member.ticketResponses,
        punishmentsIssued: member.punishmentsIssued,
        avgResponseTime: member.avgResponseTime,
        lastActive: tsToDate(member.lastActive),
      }));
    },
    staleTime: AUDIT_STALE_TIME_MS,
  });
}

export interface TicketAnalytics {
  byStatus: { status: string; count: number }[];
  byCategory: { category: string; count: number }[];
  avgResolutionByCategory: { category: string; avgHours: number }[];
  avgResolutionHours: number;
  dailyTickets: DailyCount[];
}

export function useTicketAnalytics(period: string) {
  return useQuery({
    queryKey: ['ticket-analytics', period],
    queryFn: async (): Promise<TicketAnalytics> => {
      const res = await protoFetch(
        TicketAnalyticsResponseSchema,
        `/v1/panel/analytics/tickets?period=${encodeURIComponent(period)}`,
      );
      return {
        byStatus: res.byStatus.map(({ status, count }) => ({ status, count })),
        byCategory: res.byCategory.map(({ category, count }) => ({ category, count })),
        avgResolutionByCategory: res.avgResolutionByCategory.map(({ category, avgHours }) => ({ category, avgHours })),
        avgResolutionHours: res.avgResolutionHours,
        dailyTickets: fillDailySeries(res.dailyTickets.map(({ date, count }) => ({ date, count })), period, new Date(), emptyDailyCount),
      };
    },
    staleTime: AUDIT_STALE_TIME_MS,
  });
}

export type PunishmentIssuerKind = 'staff' | 'console' | 'automated' | 'unknown';

export interface StaffPunishmentCount {
  kind: PunishmentIssuerKind;
  username: string;
  count: number;
}

export interface PunishmentAnalytics {
  byType: { type: string; count: number }[];
  dailyPunishments: DailyCount[];
  byStaff: StaffPunishmentCount[];
}

function issuerKindOf(kind: ProtoPunishmentIssuerKind): PunishmentIssuerKind {
  switch (kind) {
    case ProtoPunishmentIssuerKind.STAFF:
      return 'staff';
    case ProtoPunishmentIssuerKind.CONSOLE:
      return 'console';
    case ProtoPunishmentIssuerKind.AUTOMATED:
      return 'automated';
    case ProtoPunishmentIssuerKind.UNKNOWN:
    case ProtoPunishmentIssuerKind.UNSPECIFIED:
      return 'unknown';
  }
}

export function usePunishmentAnalytics(period: string) {
  return useQuery({
    queryKey: ['punishment-analytics', period],
    queryFn: async (): Promise<PunishmentAnalytics> => {
      const res = await protoFetch(
        PunishmentAnalyticsResponseSchema,
        `/v1/panel/analytics/punishments?period=${encodeURIComponent(period)}`,
      );
      return {
        byType: res.byType.map(({ type, count }) => ({ type, count })),
        dailyPunishments: fillDailySeries(res.dailyPunishments.map(({ date, count }) => ({ date, count })), period, new Date(), emptyDailyCount),
        byStaff: res.byStaff.map(({ kind, username, count }) => ({ kind: issuerKindOf(kind), username, count })),
      };
    },
    staleTime: AUDIT_STALE_TIME_MS,
  });
}

export interface PlayerActivity {
  newPlayersTrend: DailyCount[];
  loginsByCountry: { country: string; count: number }[];
  vpnYesCount: number;
  vpnNoCount: number;
  vpnUnknownCount: number;
  hostingCount: number;
}

export function usePlayerActivity(period: string) {
  return useQuery({
    queryKey: ['player-activity', period],
    queryFn: async (): Promise<PlayerActivity> => {
      const res = await protoFetch(
        PlayerActivityResponseSchema,
        `/v1/panel/analytics/player-activity?period=${encodeURIComponent(period)}`,
      );
      return {
        newPlayersTrend: fillDailySeries(res.newPlayersTrend.map(({ date, count }) => ({ date, count })), period, new Date(), emptyDailyCount),
        loginsByCountry: res.loginsByCountry.map(({ country, count }) => ({ country, count })),
        vpnYesCount: res.suspiciousActivity?.vpnYesCount ?? 0,
        vpnNoCount: res.suspiciousActivity?.vpnNoCount ?? 0,
        vpnUnknownCount: res.suspiciousActivity?.vpnUnknownCount ?? 0,
        hostingCount: res.suspiciousActivity?.hostingCount ?? 0,
      };
    },
    staleTime: AUDIT_STALE_TIME_MS,
  });
}

export interface StaffDetailPunishment {
  id: string;
  playerId: string;
  playerName: string;
  type: string;
  banLike: boolean;
  durationMs: number | null;
  issued: Date | null;
  active: boolean;
  rolledBack: boolean;
}

export interface StaffDetailTicket {
  id: string;
  subject: string;
  status: string;
  lastActivity: Date | null;
}

export interface StaffDetailSummary {
  totalPunishments: number;
  totalTickets: number;
  avgResponseMinutes: number;
  evidenceUploads: number;
}

export interface StaffDetails {
  punishments: StaffDetailPunishment[];
  tickets: StaffDetailTicket[];
  dailyActivity: { date: string; punishments: number; tickets: number }[];
  punishmentTypeBreakdown: { type: string; count: number }[];
  summary: StaffDetailSummary;
}

const BAN_CATEGORY = 'BAN';

function isBanCategory(effectiveCategory: string | undefined): boolean {
  return effectiveCategory === BAN_CATEGORY;
}

function parseDurationMs(duration: string): number | null {
  const millis = Number(duration);
  return Number.isFinite(millis) && millis > 0 ? millis : null;
}

export function useStaffDetails(username: string, period: string, { enabled = true }: QueryGate = {}) {
  return useQuery({
    queryKey: ['staff-details', username, period],
    queryFn: async (): Promise<StaffDetails> => {
      const res = await protoFetch(
        StaffDetailsResponseSchema,
        `/v1/panel/audit/staff/${encodeURIComponent(username)}/details?period=${encodeURIComponent(period)}`,
      );
      return {
        punishments: res.punishments.map((punishment) => ({
          id: punishment.id,
          playerId: punishment.playerId,
          playerName: punishment.playerName,
          type: punishment.type,
          banLike: isBanCategory(punishment.effectiveCategory),
          durationMs: parseDurationMs(punishment.duration),
          issued: tsToDate(punishment.issued),
          active: punishment.active,
          rolledBack: punishment.rolledBack,
        })),
        tickets: res.tickets.map((ticket) => ({
          id: ticket.id,
          subject: ticket.subject,
          status: ticket.status,
          lastActivity: tsToDate(ticket.lastActivity),
        })),
        dailyActivity: fillDailySeries(
          res.dailyActivity.map(({ date, punishments, tickets }) => ({ date, punishments, tickets })),
          period,
          new Date(),
          (date) => ({ date, punishments: 0, tickets: 0 }),
        ),
        punishmentTypeBreakdown: res.punishmentTypeBreakdown.map(({ type, count }) => ({ type, count })),
        summary: {
          totalPunishments: res.summary?.totalPunishments ?? 0,
          totalTickets: res.summary?.totalTickets ?? 0,
          avgResponseMinutes: res.summary?.avgResponseTime ?? 0,
          evidenceUploads: res.summary?.evidenceUploads ?? 0,
        },
      };
    },
    enabled,
    staleTime: AUDIT_STALE_TIME_MS,
  });
}

export interface AuditPunishmentEvidence {
  key: string;
  position: number;
  text: string;
  url: string;
  type: string;
  fileName: string;
}

export interface AuditPunishment {
  id: string;
  playerId: string;
  playerName: string;
  type: string;
  typeOrdinal: number;
  category: string;
  banLike: boolean;
  staffName: string;
  issued: Date | null;
  started: Date | null;
  expires: Date | null;
  durationMs: number | null;
  phase: AuditPunishmentPhase;
  hasEvidence: boolean;
  evidence: AuditPunishmentEvidence[];
}

export type AuditPunishmentPhase = 'active' | 'unstarted' | 'ended';
export type AuditPunishmentStatusFilter = AuditPunishmentPhase | 'all';
export type AuditPunishmentSortField = 'issued' | 'started' | 'duration';
export type AuditPunishmentSortDirection = 'asc' | 'desc';
export type AuditPunishmentEvidenceFilter = 'all' | 'yes' | 'no';

export interface AuditPunishmentQuery {
  status: AuditPunishmentStatusFilter;
  sort: AuditPunishmentSortField;
  direction: AuditPunishmentSortDirection;
  staff: string | null;
  typeOrdinal: number | null;
  evidence: AuditPunishmentEvidenceFilter;
}

interface AuditPunishmentPage {
  punishments: AuditPunishment[];
  nextCursor: string | undefined;
}

function auditPunishmentsPath(query: AuditPunishmentQuery, cursor: string): string {
  const params = new URLSearchParams({
    status: query.status,
    sort: query.sort,
    direction: query.direction,
    evidence: query.evidence,
    limit: String(AUDIT_LIST_PAGE_SIZE),
  });
  if (query.staff !== null) params.set('staff', query.staff);
  if (query.typeOrdinal !== null) params.set('type', String(query.typeOrdinal));
  if (cursor !== '') params.set('cursor', cursor);
  return `/v1/panel/audit/punishments?${params.toString()}`;
}

function phaseOf(phase: ProtoAuditPunishmentPhase): AuditPunishmentPhase {
  switch (phase) {
    case ProtoAuditPunishmentPhase.ACTIVE:
      return 'active';
    case ProtoAuditPunishmentPhase.UNSTARTED:
      return 'unstarted';
    case ProtoAuditPunishmentPhase.ENDED:
    case ProtoAuditPunishmentPhase.UNSPECIFIED:
      return 'ended';
  }
}

function positiveOrNull(millis: number): number | null {
  return millis > 0 ? millis : null;
}

function flattenAuditPunishmentPages(data: InfiniteData<AuditPunishmentPage>): AuditPunishment[] {
  return data.pages.flatMap((page) => page.punishments);
}

export function useAuditPunishments(query: AuditPunishmentQuery) {
  return useInfiniteQuery({
    queryKey: ['punishments-list', query],
    initialPageParam: '',
    queryFn: async ({ pageParam }): Promise<AuditPunishmentPage> => {
      const res = await protoFetch(AuditPunishmentsResponseSchema, auditPunishmentsPath(query, pageParam));
      const punishments = res.punishments.map((punishment) => {
        const started = tsToDate(punishment.started);
        const expires = tsToDate(punishment.expires);
        return {
          id: punishment.id,
          playerId: punishment.playerId,
          playerName: punishment.playerName,
          type: punishment.type,
          typeOrdinal: punishment.typeOrdinal,
          category: punishment.category,
          banLike: isBanCategory(punishment.effectiveCategory),
          staffName: punishment.staffName,
          issued: tsToDate(punishment.issued),
          started,
          expires,
          durationMs: punishment.duration === undefined ? null : positiveOrNull(toNum(punishment.duration)),
          phase: phaseOf(punishment.phase),
          hasEvidence: punishment.hasEvidence,
          evidence: punishment.evidence.map(({ text, url, type, fileName }, index) => ({
            key: `${punishment.id}:${String(index)}`,
            position: index + 1,
            text,
            url,
            type,
            fileName,
          })),
        };
      });
      return { punishments, nextCursor: res.hasMore ? res.nextCursor : undefined };
    },
    getNextPageParam: (lastPage) => lastPage.nextCursor,
    select: flattenAuditPunishmentPages,
    staleTime: AUDIT_STALE_TIME_MS,
  });
}

export interface AuditPunishmentType {
  ordinal: number;
  name: string;
  activeCount: number;
}

export function useAuditPunishmentTypes({ enabled = true }: QueryGate = {}) {
  return useQuery({
    queryKey: AUDIT_PUNISHMENT_TYPES_KEY,
    enabled,
    queryFn: async (): Promise<AuditPunishmentType[]> => {
      const res = await protoFetch(AuditPunishmentTypesResponseSchema, '/v1/panel/audit/punishments/types');
      return res.types.map(({ ordinal, name, activeCount }) => ({ ordinal, name, activeCount }));
    },
    staleTime: AUDIT_STALE_TIME_MS,
  });
}

export type BulkPunishmentAction =
  | { kind: 'pardon'; typeOrdinals: readonly number[]; reason: string }
  | { kind: 'set-expiration'; typeOrdinals: readonly number[]; reason: string; newDurationMs: number };

function bulkPunishmentActionPath(action: BulkPunishmentAction): string {
  switch (action.kind) {
    case 'pardon':
      return '/v1/panel/audit/punishments/bulk-pardon';
    case 'set-expiration':
      return '/v1/panel/audit/punishments/bulk-set-expiration';
  }
}

function bulkPunishmentActionRequest(action: BulkPunishmentAction) {
  return create(BulkPunishmentActionRequestSchema, {
    typeOrdinals: [...action.typeOrdinals],
    reason: action.reason,
    ...(action.kind === 'set-expiration' ? { newDurationMs: BigInt(action.newDurationMs) } : {}),
  });
}

export function useBulkPunishmentAction() {
  return useMutation({
    mutationFn: async (action: BulkPunishmentAction): Promise<number> => {
      const res = await protoSend(
        'POST',
        bulkPunishmentActionPath(action),
        BulkPunishmentActionRequestSchema,
        bulkPunishmentActionRequest(action),
        AuditBulkOperationResponseSchema,
      );
      return res.count;
    },
    onSuccess: invalidatePunishmentChange,
  });
}

export function useRollbackAuditPunishment() {
  return useMutation({
    mutationFn: async ({ punishmentId, reason }: { punishmentId: string; reason: string }) =>
      protoSend(
        'POST',
        `/v1/panel/audit/punishments/${encodeURIComponent(punishmentId)}/rollback`,
        RollbackRequestSchema,
        create(RollbackRequestSchema, { reason }),
        AuditRollbackResponseSchema,
      ),
    onSuccess: invalidatePunishmentChange,
  });
}
