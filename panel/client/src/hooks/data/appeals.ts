import { useQuery, useMutation } from '@tanstack/react-query';
import { fromJson, toJson, type JsonObject, type JsonValue } from '@bufbuild/protobuf';
import { z } from 'zod';
import { queryClient } from '../../lib/queryClient';
import { ProtoHttpError, protoFetch, protoFetchOrNull, protoSend } from '@/lib/proto-fetch';
import { apiErrorCode } from '@/lib/http-error';
import { parseAppealFormSettings } from '@/lib/appeal-form';
import type { AppealFormSettings } from '@/types/forms';
import { PublicPunishmentAppealInfoResponseSchema, type PublicPunishmentAppealInfoResponse } from '@proto/modl/v1/punishment_pb.ts';
import { definedJsonFields, tsToIso } from '@/lib/proto-ui';
import {
  AddAppealReplyRequestSchema,
  AddPublicAppealReplyResponseSchema,
  AppealTicketsResponseSchema,
  CreateAppealRequestSchema,
  CreatePublicAppealResponseSchema,
  PublicAppealResponseSchema,
  type PublicAppealResponse,
} from '@proto/modl/v1/appeal_pb.ts';
import type { TicketReply, TicketResponse } from '@proto/modl/v1/ticket_pb.ts';
import {
  fetchPublicRecord,
  postPublicRecord,
  requestPublicVerification,
  verifyPublicCode,
  type PublicRecordVerificationRequired,
} from './public-verification';

const publicAppealAttachmentSchema = z.object({
  id: z.string().optional(),
  url: z.string(),
  fileName: z.string().optional(),
  fileType: z.string().catch('application/octet-stream'),
  fileSize: z.number().optional(),
});

export type PublicAppealAttachment = z.infer<typeof publicAppealAttachmentSchema>;

export interface PublicAppealReply {
  id: string;
  name: string;
  content: string;
  staff: boolean;
  created: string | null;
  attachments: PublicAppealAttachment[];
}

export interface PublicAppeal {
  id: string;
  status: string;
  appealWorkflowStatus: string;
  locked: boolean;
  created: string | null;
  punishmentId: string;
  messages: PublicAppealReply[];
}

export interface PublicAppealReplyInput {
  name: string;
  content: string;
  staff: boolean;
  attachments: JsonObject[];
}

function mapPublicAppealAttachments(attachments: readonly JsonObject[]): PublicAppealAttachment[] {
  return attachments.flatMap((attachment) => {
    const parsed = publicAppealAttachmentSchema.safeParse(attachment);
    return parsed.success ? [parsed.data] : [];
  });
}

function epochMillisToIso(millis: bigint): string | null {
  return millis > 0n ? new Date(Number(millis)).toISOString() : null;
}

function mapPublicAppealReply(reply: TicketReply): PublicAppealReply {
  return {
    id: reply.id,
    name: reply.name,
    content: reply.content,
    staff: reply.staff,
    created: epochMillisToIso(reply.created),
    attachments: mapPublicAppealAttachments(reply.attachments),
  };
}

function textOf(value: JsonValue | undefined): string {
  return typeof value === 'string' ? value : '';
}

function mapPublicAppeal(appeal: PublicAppealResponse): PublicAppeal {
  return {
    id: appeal.id,
    status: appeal.status,
    appealWorkflowStatus: appeal.appealWorkflowStatus,
    locked: appeal.locked,
    created: tsToIso(appeal.created),
    punishmentId: textOf(appeal.data?.['punishmentId']),
    messages: appeal.messages.map(mapPublicAppealReply),
  };
}

export async function fetchPublicAppeal(appealId: string): Promise<PublicAppeal | PublicRecordVerificationRequired | null> {
  const appeal = await fetchPublicRecord('appeals', appealId, PublicAppealResponseSchema);
  if (appeal === null || 'requiresVerification' in appeal) return appeal;
  return mapPublicAppeal(appeal);
}

export async function postPublicAppealReply(appealId: string, reply: PublicAppealReplyInput) {
  const request = fromJson(AddAppealReplyRequestSchema, { ...reply });
  return postPublicRecord(
    'appeals',
    appealId,
    '/replies',
    toJson(AddAppealReplyRequestSchema, request),
    AddPublicAppealReplyResponseSchema,
  );
}

const existingAppealSchema = z.object({
  id: z.string(),
  submittedDate: z.string().catch(''),
  status: z.string().catch(''),
  appealWorkflowStatus: z.string().catch(''),
  locked: z.boolean().catch(false),
});

export type ExistingAppealSummary = z.infer<typeof existingAppealSchema>;

export interface PublicPunishmentAppealInfo {
  id: string;
  type: string;
  issued: Date | null;
  expires: Date | null;
  active: boolean;
  appealable: boolean;
  playerUuid: string;
  existingAppeal: ExistingAppealSummary | null;
  appealForm: AppealFormSettings | null;
}

export type PunishmentAppealLookup =
  | { kind: 'found'; info: PublicPunishmentAppealInfo }
  | { kind: 'notStarted' };

function epochMillisToDate(millis: bigint | undefined): Date | null {
  return millis !== undefined && millis > 0n ? new Date(Number(millis)) : null;
}

function mapPunishmentAppealInfo(info: PublicPunishmentAppealInfoResponse): PublicPunishmentAppealInfo {
  return {
    id: info.id,
    type: info.type,
    issued: epochMillisToDate(info.issued),
    expires: epochMillisToDate(info.expires),
    active: info.active,
    appealable: info.appealable,
    playerUuid: info.playerUuid,
    existingAppeal: existingAppealSchema.safeParse(info.existingAppeal).data ?? null,
    appealForm: info.appealForm === undefined ? null : parseAppealFormSettings(info.appealForm),
  };
}

const PUNISHMENT_NOT_STARTED_CODE = 'PUNISHMENT_NOT_STARTED';

function isPunishmentNotStartedError(error: unknown): boolean {
  return error instanceof ProtoHttpError && apiErrorCode(error) === PUNISHMENT_NOT_STARTED_CODE;
}

export async function fetchPunishmentAppealInfo(punishmentId: string): Promise<PunishmentAppealLookup> {
  try {
    const info = await protoFetch(
      PublicPunishmentAppealInfoResponseSchema,
      `/v1/public/punishment/${encodeURIComponent(punishmentId)}/appeal-info`,
    );
    return { kind: 'found', info: mapPunishmentAppealInfo(info) };
  } catch (error) {
    if (isPunishmentNotStartedError(error)) return { kind: 'notStarted' };
    throw error;
  }
}

function mapAppealTicket(ticket: TicketResponse) {
  return {
    ...ticket,
    _id: ticket.id,
    date: Number(ticket.date),
    messages: ticket.messages.map((reply) => ({ ...reply, created: Number(reply.created) })),
    notes: ticket.notes.map((note) => ({ ...note, date: Number(note.date) })),
  };
}

export function useAppeals() {
  return useQuery({
    queryKey: ['/v1/panel/appeals'],
    queryFn: async () => {
      const response = await protoFetch(AppealTicketsResponseSchema, '/v1/panel/appeals');
      return response.tickets.map(mapAppealTicket);
    }
  });
}

export function useAppealsByPunishment(punishmentId: string) {
  return useQuery({
    queryKey: ['/v1/panel/appeals/punishment', punishmentId],
    queryFn: async () => {
      const response = await protoFetchOrNull(
        AppealTicketsResponseSchema,
        `/v1/panel/appeals/punishment/${punishmentId}`,
      );
      return response ? response.tickets.map(mapAppealTicket) : [];
    },
    enabled: !!punishmentId
  });
}

interface CreateAppealInput {
  punishmentId: JsonValue;
  playerUuid: JsonValue;
  email: string;
  attachments?: JsonValue;
  reason?: JsonValue | undefined;
  evidence?: JsonValue | undefined;
  additionalData?: JsonValue;
  fieldLabels?: JsonValue;
}

export function useCreateAppeal() {
  return useMutation({
    mutationFn: async (appealData: CreateAppealInput) => {
      const json: JsonObject = {
        punishmentId: appealData.punishmentId,
        playerUuid: appealData.playerUuid,
        attachments: appealData.attachments ?? [],
        ...definedJsonFields({
          email: appealData.email || undefined,
          reason: appealData.reason,
          evidence: appealData.evidence,
          additionalData: appealData.additionalData,
          fieldLabels: appealData.fieldLabels,
        }),
      };

      return protoSend(
        'POST',
        '/v1/public/appeals',
        CreateAppealRequestSchema,
        fromJson(CreateAppealRequestSchema, json, { ignoreUnknownFields: true }),
        CreatePublicAppealResponseSchema,
      );
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['/v1/panel/appeals'] });
    }
  });
}

export function useRequestAppealVerification() {
  return useMutation({
    mutationFn: async (appealId: string) => requestPublicVerification('appeals', appealId)
  });
}

export function useVerifyAppealCode() {
  return useMutation({
    mutationFn: async ({ appealId, code }: { appealId: string, code: string }) =>
      verifyPublicCode('appeals', appealId, code)
  });
}
