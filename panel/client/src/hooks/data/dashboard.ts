import { queryOptions, useQuery } from '@tanstack/react-query';
import {
  DashboardMetricsResponseSchema,
  DashboardRecentTicketsResponseSchema,
  DashboardRecentPunishmentsResponseSchema,
} from '@proto/modl/v1/dashboard_pb.ts';
import { protoFetch } from '@/lib/proto-fetch';
import { toNum } from '@/lib/proto-ui';
import type { QueryGate } from './query-gate';

export interface DashboardMetrics {
  totalTickets: number;
  openTickets: number;
  totalPlayers: number;
  totalPunishments: number;
  activePunishments: number;
  totalStaff: number;
  ticketsTrend: number;
  playersTrend: number;
}

export function useDashboardMetrics(period = '7d') {
  return useQuery({
    queryKey: ['/v1/panel/dashboard/metrics', period],
    queryFn: async (): Promise<DashboardMetrics> => {
      const res = await protoFetch(
        DashboardMetricsResponseSchema,
        `/v1/panel/dashboard/metrics?period=${period}`,
      );
      return {
        totalTickets: toNum(res.totalTickets),
        openTickets: toNum(res.openTickets),
        totalPlayers: toNum(res.totalPlayers),
        totalPunishments: toNum(res.totalPunishments),
        activePunishments: toNum(res.activePunishments),
        totalStaff: toNum(res.totalStaff),
        ticketsTrend: res.ticketsTrend,
        playersTrend: res.playersTrend,
      };
    },
    staleTime: 5 * 60 * 1000,
    refetchOnWindowFocus: true,
  });
}

export interface RecentTicket {
  id: string;
  title: string;
  initialMessage: string;
  status: string;
  priority: string;
  createdAt: Date;
  playerName: string;
  type: string;
}

export function recentTicketsQuery(limit: number) {
  return queryOptions({
    queryKey: ['/v1/panel/dashboard/recent-tickets', limit],
    queryFn: async (): Promise<RecentTicket[]> => {
      const res = await protoFetch(
        DashboardRecentTicketsResponseSchema,
        `/v1/panel/dashboard/recent-tickets?limit=${limit}`,
      );
      return res.tickets.map((ticket) => ({
        id: ticket.id,
        title: ticket.title,
        initialMessage: ticket.initialMessage,
        status: ticket.status,
        priority: ticket.priority,
        createdAt: new Date(toNum(ticket.createdAt)),
        playerName: ticket.playerName,
        type: ticket.type,
      }));
    },
    staleTime: 2 * 60 * 1000,
  });
}

export function useRecentTickets(limit = 5, { enabled = true }: QueryGate = {}) {
  return useQuery({ ...recentTicketsQuery(limit), enabled, refetchOnWindowFocus: false });
}

export interface RecentPunishment {
  id: string;
  playerName: string;
  playerUuid: string;
  type: string;
  reason: string;
  issuerName: string;
  issued: Date;
  active: boolean;
}

export function recentPunishmentsQuery(limit: number) {
  return queryOptions({
    queryKey: ['/v1/panel/dashboard/recent-punishments', limit],
    queryFn: async (): Promise<RecentPunishment[]> => {
      const res = await protoFetch(
        DashboardRecentPunishmentsResponseSchema,
        `/v1/panel/dashboard/recent-punishments?limit=${limit}`,
      );
      return res.punishments.map((punishment) => ({
        id: punishment.id,
        playerName: punishment.playerName,
        playerUuid: punishment.playerUuid,
        type: punishment.type,
        reason: punishment.reason,
        issuerName: punishment.issuerName,
        issued: new Date(toNum(punishment.issued)),
        active: punishment.active,
      }));
    },
    staleTime: 2 * 60 * 1000,
  });
}

export function useRecentPunishments(limit = 10, { enabled = true }: QueryGate = {}) {
  return useQuery({ ...recentPunishmentsQuery(limit), enabled, refetchOnWindowFocus: false });
}
