import { queryOptions, useQuery, useMutation, keepPreviousData } from '@tanstack/react-query';
import { z } from 'zod';
import { create, toJson, type JsonObject, type JsonValue, type MessageInitShape, type MessageShape } from '@bufbuild/protobuf';
import type { Timestamp } from '@bufbuild/protobuf/wkt';
import { queryClient } from '../../lib/queryClient';
import { apiFetch } from '@/lib/api';
import { parseJsonOrNull } from '@/lib/http-error';
import { ProtoHttpError, protoFetch, protoFetchOrNull, protoHttpErrorOf, protoSend } from '@/lib/proto-fetch';
import { optionalField } from '@/lib/defined-fields';
import { definedJsonFields, tsToDate } from '@/lib/proto-ui';
import {
  PaginatedTicketsResponseSchema,
  type PaginatedTicketsResponse,
  type TicketListItemResponse,
  TicketResponseSchema,
  type TicketResponse,
  type TicketReply,
  type TicketNote,
  type TicketChatMessage,
  type AIAnalysisResult,
  TicketCountsResponseSchema,
  CreateTicketRequestSchema,
  CreateTicketResponseSchema,
  UpdateTicketRequestSchema,
  AddReplyRequestSchema,
  type AddReplyRequest,
  AddNoteRequestSchema,
  AddTagRequestSchema,
  TicketAssigneesSchema,
  TicketTagsResponseSchema,
  AddTicketReplyResponseSchema,
  SubmitTicketFormRequestSchema,
  SubmitPublicTicketResponseSchema,
  BulkTicketUpdateRequestSchema,
  BulkTicketUpdateResponseSchema,
  PublicTicketResponseSchema,
  type PublicTicketResponse,
  type PublicTicketReply,
  type PublicTicketNote,
} from '@proto/modl/v1/ticket_pb.ts';
import { AISuggestionActionResponseSchema, ApplyAIPunishmentRequestSchema } from '@proto/modl/v1/settings_pb.ts';

import {
  fetchPublicRecord,
  postPublicRecord,
  requestPublicVerification,
  verifyPublicCode,
} from './public-verification';

export function publicTicketQueryKey(id: string) {
  return ['/v1/public/tickets', id] as const;
}

const millisToNumber = (v: bigint): number => Number(v);

const timestampToIso = (t?: Timestamp): string | undefined => {
  const date = tsToDate(t);
  return date ? date.toISOString() : undefined;
};

function mapReply(reply: TicketReply) {
  return { ...reply, created: millisToNumber(reply.created) };
}

function readCreatorEmail(data: JsonObject | undefined): string | undefined {
  const raw = data?.['creatorEmail'] ?? data?.['contactEmail'];
  return typeof raw === 'string' && raw.trim().length > 0 ? raw.trim() : undefined;
}

function accessKeyOfPublicUrl(publicUrl: string): string | null {
  if (!publicUrl) return null;
  try {
    return new URL(publicUrl).searchParams.get('key');
  } catch {
    return null;
  }
}

function mapListItem(item: TicketListItemResponse) {
  return {
    ...item,
    date: millisToNumber(item.date),
    lastReply: item.lastReply ? mapReply(item.lastReply) : undefined,
  };
}

export type TicketListItem = ReturnType<typeof mapListItem>;

function mapPaginatedTickets(response: PaginatedTicketsResponse) {
  return {
    tickets: response.tickets.map(mapListItem),
    pagination: response.pagination
      ? { ...response.pagination, totalTickets: Number(response.pagination.totalTickets) }
      : undefined,
    filters: response.filters,
  };
}

const publicReplyAttachmentSchema = z.object({
  url: z.string(),
  fileName: z.string().optional().catch(undefined),
});

export type PublicTicketReplyAttachment = z.infer<typeof publicReplyAttachmentSchema>;

function mapPublicReplyAttachments(attachments: readonly JsonObject[]): PublicTicketReplyAttachment[] {
  return attachments.flatMap((attachment) => {
    const parsed = publicReplyAttachmentSchema.safeParse(attachment);
    return parsed.success ? [parsed.data] : [];
  });
}

function mapPublicReply(reply: PublicTicketReply) {
  return { ...reply, created: timestampToIso(reply.created), attachments: mapPublicReplyAttachments(reply.attachments) };
}

function mapPublicNote(note: PublicTicketNote) {
  return { ...note, date: timestampToIso(note.date) };
}

function mapPublicTicketResponse(ticket: PublicTicketResponse) {
  return {
    ...ticket,
    _id: ticket.id,
    created: timestampToIso(ticket.created),
    date: timestampToIso(ticket.date),
    replies: ticket.replies.map(mapPublicReply),
    messages: ticket.messages.map(mapPublicReply),
    notes: ticket.notes.map(mapPublicNote),
    chatMessages: ticket.chatMessages.map((m) => ({ ...m, timestamp: timestampToIso(m.timestamp) })),
  };
}

export type TicketAttachmentInput = string | Readonly<Record<string, JsonValue | undefined>>;

function toAttachmentStructs(attachments: readonly TicketAttachmentInput[] = []): JsonObject[] {
  return attachments.map((attachment) =>
    typeof attachment === 'string' ? { url: attachment } : definedJsonFields(attachment),
  );
}

export interface TicketListFilters {
  page?: number;
  limit?: number;
  search?: string;
  status?: string;
  types?: readonly string[];
  author?: string;
  labels?: readonly string[];
  assignees?: readonly string[];
  sort?: string;
}

export const DEFAULT_TICKET_LIST_FILTERS = {
  page: 1,
  limit: 25,
  search: '',
  status: 'open',
  types: [],
  author: '',
  labels: [],
  assignees: [],
  sort: 'newest',
} as const satisfies Required<TicketListFilters>;

export function ticketsQuery(filters: TicketListFilters = {}) {
  const {
    page = DEFAULT_TICKET_LIST_FILTERS.page,
    limit = DEFAULT_TICKET_LIST_FILTERS.limit,
    search = DEFAULT_TICKET_LIST_FILTERS.search,
    status = DEFAULT_TICKET_LIST_FILTERS.status,
    types = DEFAULT_TICKET_LIST_FILTERS.types,
    author = DEFAULT_TICKET_LIST_FILTERS.author,
    labels = DEFAULT_TICKET_LIST_FILTERS.labels,
    assignees = DEFAULT_TICKET_LIST_FILTERS.assignees,
    sort = DEFAULT_TICKET_LIST_FILTERS.sort,
  } = filters;

  return queryOptions({
    queryKey: ['/v1/panel/tickets', { page, limit, search, status, types, author, labels, assignees, sort }],
    queryFn: async () => {
      const params = new URLSearchParams();
      params.append('page', page.toString());
      params.append('limit', limit.toString());
      if (search) params.append('search', search);
      if (status) params.append('status', status);
      if (types.length > 0) {
        types.forEach(type => params.append('type', type));
      }
      if (author) params.append('author', author);
      if (labels.length > 0) {
        labels.forEach(label => params.append('labels', label));
      }
      if (assignees.length > 0) {
        assignees.forEach(assignee => params.append('assignee', assignee));
      }
      if (sort) params.append('sort', sort);

      const response = await protoFetch(PaginatedTicketsResponseSchema, `/v1/panel/tickets?${params.toString()}`);
      return mapPaginatedTickets(response);
    },
    staleTime: 30000,
  });
}

export function useTickets(filters?: TicketListFilters) {
  return useQuery({ ...ticketsQuery(filters), refetchOnWindowFocus: true, placeholderData: keepPreviousData });
}

export function useTicket(id: string, accessReady: boolean) {
  return useQuery({
    queryKey: publicTicketQueryKey(id),
    queryFn: async () => {
      const ticket = await fetchPublicRecord('tickets', id, PublicTicketResponseSchema);
      if (ticket === null || 'requiresVerification' in ticket) return ticket;
      return mapPublicTicketResponse(ticket);
    },
    enabled: !!id && accessReady,
    retry: false,
    staleTime: 30000,
    gcTime: 1000 * 60 * 10,
    refetchOnWindowFocus: true
  });
}

export type PublicTicket = ReturnType<typeof mapPublicTicketResponse>;

export interface PublicTicketCreation {
  type: string;
  subject: string;
  creatorName: string;
  creatorEmail: string | null;
  formData: JsonObject;
  fieldLabels: Record<string, string>;
  attachments: readonly JsonObject[];
  creatorIdentifier: string;
  emailAuthEnabled: boolean;
}

export function useCreatePublicTicket() {
  return useMutation({
    mutationFn: async (ticket: PublicTicketCreation) => {
      const request = create(CreateTicketRequestSchema, {
        type: ticket.type,
        subject: ticket.subject,
        creatorName: ticket.creatorName,
        creatorEmail: ticket.creatorEmail ?? undefined,
        formData: ticket.formData,
        fieldLabels: ticket.fieldLabels,
        attachments: [...ticket.attachments],
        creatorIdentifier: ticket.creatorIdentifier,
        emailAuthEnabled: ticket.emailAuthEnabled,
      });
      return protoSend('POST', '/v1/public/tickets', CreateTicketRequestSchema, request, CreateTicketResponseSchema);
    },
  });
}

export function useCreateTicket() {
  return useMutation({
    mutationFn: async (ticketData: MessageInitShape<typeof CreateTicketRequestSchema>) =>
      protoSend(
        'POST',
        '/v1/panel/tickets',
        CreateTicketRequestSchema,
        create(CreateTicketRequestSchema, ticketData),
        CreateTicketResponseSchema,
      ),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['/v1/panel/tickets'] });
    }
  });
}

interface TicketReplyInput {
  content?: string;
  attachments?: readonly TicketAttachmentInput[];
  action?: string;
  creatorIdentifier?: string;
}

interface TicketNoteInput {
  text?: string;
  attachments?: readonly TicketAttachmentInput[];
}

interface UpdateTicketInput {
  status?: string;
  locked?: boolean;
  hidden?: boolean;
  tags?: string[];
  assignedTo?: string[];
  data?: JsonObject;
  newReply?: TicketReplyInput;
  newNote?: TicketNoteInput;
}

interface UpdateTicketVariables {
  id: string;
  data: UpdateTicketInput;
  author?: string;
}

function toAddNoteRequest({ attachments, ...fields }: TicketNoteInput) {
  return create(AddNoteRequestSchema, { ...fields, attachments: toAttachmentStructs(attachments) });
}

function toAddReplyRequest({ attachments, ...fields }: TicketReplyInput): AddReplyRequest {
  return create(AddReplyRequestSchema, { ...fields, attachments: toAttachmentStructs(attachments) });
}

interface SubmitTicketFormInput {
  subject?: string;
  creatorEmail?: string;
  emailAuthEnabled?: boolean;
  formData?: JsonObject;
  attachments?: readonly TicketAttachmentInput[];
  creatorIdentifier?: string;
  fieldLabels?: Record<string, string>;
}

const optionalText = z.string().nullish().transform((value) => value ?? undefined).catch(undefined);
const textList = z.array(z.string()).nullish().transform((value) => value ?? []).catch([]);

const playerTicketSchema = z.object({
  id: optionalText,
  subject: optionalText,
  status: optionalText,
  type: optionalText,
  created: z.union([z.string(), z.number()]).nullish().catch(undefined),
  creatorName: optionalText,
  creatorUuid: optionalText,
  reportedPlayer: optionalText,
  reportedPlayerUuid: optionalText,
  locked: z.boolean().catch(false),
  tags: textList,
  assignedTo: textList,
});

export type PlayerTicket = Omit<z.infer<typeof playerTicketSchema>, 'created'> & { created: string | null };

function isoDateOf(value: string | number | null | undefined): string | null {
  if (value === null || value === undefined || value === '') return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

function parsePlayerTickets(payload: unknown): PlayerTicket[] {
  if (!Array.isArray(payload)) return [];
  return payload.flatMap((entry: unknown) => {
    const parsed = playerTicketSchema.safeParse(entry);
    return parsed.success ? [{ ...parsed.data, created: isoDateOf(parsed.data.created) }] : [];
  });
}

const ticketAttachmentSchema = z.object({
  url: optionalText,
  fileName: optionalText,
  fileType: optionalText,
});

export type TicketAttachment = z.infer<typeof ticketAttachmentSchema>;

export interface PanelTicketReply {
  id: string;
  name: string;
  avatar: string;
  content: string;
  type: string;
  created: number;
  staff: boolean;
  action: string;
  attachments: TicketAttachment[];
  creatorIdentifier: string;
}

export interface PanelTicketNote {
  text: string;
  issuerName: string;
  date: number;
  attachments: TicketAttachment[];
}

export interface PanelTicketChatLine {
  sender: string;
  text: string;
  timestamp: number | null;
}

export interface PanelTicketAiSuggestedAction {
  punishmentTypeId: number;
  severity: string;
}

export interface PanelTicketAiAnalysis {
  analysis: string;
  suggestedAction: PanelTicketAiSuggestedAction | null;
  wasAppliedAutomatically: boolean;
  appliedBy: string | undefined;
  dismissed: boolean;
  createdAt: number;
}

export interface PanelTicket {
  id: string;
  type: string;
  subject: string;
  status: string;
  locked: boolean;
  hidden: boolean;
  creatorName: string;
  creatorUuid: string;
  creatorEmail: string | undefined;
  emailAuthEnabled: boolean;
  reportedBy: string;
  reportedPlayer: string;
  reportedPlayerUuid: string;
  date: number;
  messages: PanelTicketReply[];
  notes: PanelTicketNote[];
  tags: string[];
  assignedTo: string[];
  chatMessages: PanelTicketChatLine[];
  aiAnalysis: PanelTicketAiAnalysis | null;
  replayUrl: string;
  publicUrl: string;
  publicAccessKey: string | null;
  appealPunishmentId: string | undefined;
  appealPlayerUuid: string | undefined;
}

const legacyChatLineSchema = z.object({
  username: optionalText,
  sender: optionalText,
  message: optionalText,
  content: optionalText,
  timestamp: z.union([z.string(), z.number()]).nullish().catch(undefined),
});

function finiteMillisOrNull(value: string | number | null | undefined): number | null {
  if (value === null || value === undefined) return null;
  const millis = typeof value === 'string' ? Date.parse(value) : value;
  return Number.isFinite(millis) && millis > 0 ? millis : null;
}

function parseAttachments(attachments: readonly unknown[]): TicketAttachment[] {
  return attachments.flatMap((attachment) => {
    const parsed = ticketAttachmentSchema.safeParse(attachment);
    return parsed.success ? [parsed.data] : [];
  });
}

function nonBlankText(data: JsonObject | undefined, key: string): string | undefined {
  const value = data?.[key];
  return typeof value === 'string' && value.trim().length > 0 ? value : undefined;
}

function mapPanelReply(reply: TicketReply): PanelTicketReply {
  return {
    id: reply.id,
    name: reply.name,
    avatar: reply.avatar,
    content: reply.content,
    type: reply.type,
    created: millisToNumber(reply.created),
    staff: reply.staff,
    action: reply.action,
    attachments: parseAttachments(reply.attachments),
    creatorIdentifier: reply.creatorIdentifier,
  };
}

function mapPanelNote(note: TicketNote): PanelTicketNote {
  return {
    text: note.text,
    issuerName: note.issuerName,
    date: millisToNumber(note.date),
    attachments: parseAttachments(note.attachments),
  };
}

function mapChatLine(message: TicketChatMessage): PanelTicketChatLine {
  const timestamp = finiteMillisOrNull(millisToNumber(message.timestamp));
  if (message.sender || !message.content) {
    return { sender: message.sender, text: message.content, timestamp };
  }
  const legacy = legacyChatLineSchema.safeParse(parseJsonOrNull(message.content));
  if (!legacy.success) {
    return { sender: '', text: message.content, timestamp };
  }
  return {
    sender: legacy.data.username ?? legacy.data.sender ?? '',
    text: legacy.data.message ?? legacy.data.content ?? message.content,
    timestamp: finiteMillisOrNull(legacy.data.timestamp) ?? timestamp,
  };
}

function mapAiAnalysis(analysis: AIAnalysisResult | undefined): PanelTicketAiAnalysis | null {
  if (!analysis) return null;
  return {
    analysis: analysis.analysis,
    suggestedAction: analysis.suggestedAction
      ? { punishmentTypeId: analysis.suggestedAction.punishmentTypeId, severity: analysis.suggestedAction.severity }
      : null,
    wasAppliedAutomatically: analysis.wasAppliedAutomatically,
    appliedBy: analysis.appliedBy,
    dismissed: analysis.dismissed,
    createdAt: millisToNumber(analysis.createdAt),
  };
}

function mapPanelTicket(ticket: TicketResponse): PanelTicket {
  return {
    id: ticket.id,
    type: ticket.type,
    subject: ticket.subject,
    status: ticket.status,
    locked: ticket.locked,
    hidden: ticket.hidden,
    creatorName: ticket.creatorName,
    creatorUuid: ticket.creatorUuid,
    creatorEmail: readCreatorEmail(ticket.data),
    emailAuthEnabled: ticket.emailAuthEnabled,
    reportedBy: ticket.reportedBy,
    reportedPlayer: ticket.reportedPlayer,
    reportedPlayerUuid: ticket.reportedPlayerUuid,
    date: millisToNumber(ticket.date),
    messages: ticket.messages.map(mapPanelReply),
    notes: ticket.notes.map(mapPanelNote),
    tags: ticket.tags,
    assignedTo: ticket.assignedTo,
    chatMessages: ticket.chatMessages.map(mapChatLine),
    aiAnalysis: mapAiAnalysis(ticket.aiAnalysis),
    replayUrl: ticket.replayUrl,
    publicUrl: ticket.publicUrl,
    publicAccessKey: accessKeyOfPublicUrl(ticket.publicUrl),
    appealPunishmentId: nonBlankText(ticket.data, 'punishmentId'),
    appealPlayerUuid: nonBlankText(ticket.data, 'playerUuid'),
  };
}

const PANEL_TICKETS_PATH = '/v1/panel/tickets';
const UPDATE_TICKET_MUTATION_KEY = ['updateTicket'] as const;

function panelTicketPath(id: string): string {
  return `${PANEL_TICKETS_PATH}/${encodeURIComponent(id)}`;
}

function panelTicketQuery(id: string) {
  return queryOptions({
    queryKey: [PANEL_TICKETS_PATH, id] as const,
    queryFn: async (): Promise<PanelTicket | null> => {
      const response = await protoFetchOrNull(TicketResponseSchema, panelTicketPath(id));
      return response ? mapPanelTicket(response) : null;
    },
    staleTime: 30000,
    gcTime: 1000 * 60 * 10,
  });
}

function panelTicketQueryKey(id: string) {
  return panelTicketQuery(id).queryKey;
}

function invalidateTicketCollections(exceptTicketId: string) {
  void queryClient.invalidateQueries({
    queryKey: [PANEL_TICKETS_PATH],
    predicate: (query) => query.queryKey[1] !== exceptTicketId,
  });
  void queryClient.invalidateQueries({ queryKey: ['/v1/panel/tickets/counts'] });
}

function isOnlyPendingTicketUpdate(): boolean {
  return queryClient.isMutating({ mutationKey: UPDATE_TICKET_MUTATION_KEY }) <= 1;
}

function attachmentOfInput(attachment: TicketAttachmentInput): unknown {
  return typeof attachment === 'string' ? { url: attachment } : attachment;
}

function pendingReply(reply: TicketReplyInput, author: string, created: number): PanelTicketReply {
  return {
    id: '',
    name: author,
    avatar: '',
    content: reply.content ?? '',
    type: 'staff',
    created,
    staff: true,
    action: reply.action ?? '',
    attachments: parseAttachments((reply.attachments ?? []).map(attachmentOfInput)),
    creatorIdentifier: reply.creatorIdentifier ?? '',
  };
}

function pendingNote(note: TicketNoteInput, author: string, date: number): PanelTicketNote {
  return {
    text: note.text ?? '',
    issuerName: author,
    date,
    attachments: parseAttachments((note.attachments ?? []).map(attachmentOfInput)),
  };
}

function withPendingUpdate(ticket: PanelTicket, update: UpdateTicketInput, author: string, now: number): PanelTicket {
  return {
    ...ticket,
    status: update.status ?? ticket.status,
    locked: update.locked ?? ticket.locked,
    hidden: update.hidden ?? ticket.hidden,
    tags: update.tags ?? ticket.tags,
    assignedTo: update.assignedTo ?? ticket.assignedTo,
    messages: update.newReply ? [...ticket.messages, pendingReply(update.newReply, author, now)] : ticket.messages,
    notes: update.newNote ? [...ticket.notes, pendingNote(update.newNote, author, now)] : ticket.notes,
  };
}

export function useUpdateTicket() {
  return useMutation({
    mutationKey: UPDATE_TICKET_MUTATION_KEY,
    onMutate: async ({ id, data, author }: UpdateTicketVariables) => {
      const queryKey = panelTicketQueryKey(id);
      await queryClient.cancelQueries({ queryKey });
      const previous = queryClient.getQueryData(queryKey);
      if (previous) {
        queryClient.setQueryData(queryKey, withPendingUpdate(previous, data, author ?? '', Date.now()));
      }
      return { previous };
    },
    onError: (_error, { id }, context) => {
      const queryKey = panelTicketQueryKey(id);
      if (context?.previous && isOnlyPendingTicketUpdate()) {
        queryClient.setQueryData(queryKey, context.previous);
      }
      void queryClient.invalidateQueries({ queryKey });
    },
    mutationFn: async ({ id, data }: UpdateTicketVariables) => {
      const { newReply, newNote, assignedTo, ...fields } = data;
      const request = create(UpdateTicketRequestSchema, {
        ...fields,
        ...optionalField('newReply', newReply === undefined ? undefined : toAddReplyRequest(newReply)),
        ...optionalField('newNote', newNote === undefined ? undefined : toAddNoteRequest(newNote)),
        ...optionalField('assignees', assignedTo === undefined ? undefined : create(TicketAssigneesSchema, { names: assignedTo })),
      });
      const response = await protoSend(
        'PATCH',
        panelTicketPath(id),
        UpdateTicketRequestSchema,
        request,
        TicketResponseSchema,
      );
      return mapPanelTicket(response);
    },
    onSuccess: (ticket, { id }) => {
      const queryKey = panelTicketQueryKey(id);
      if (isOnlyPendingTicketUpdate()) {
        queryClient.setQueryData(queryKey, ticket);
      } else {
        void queryClient.invalidateQueries({ queryKey });
      }
      invalidateTicketCollections(id);
    }
  });
}

function withTags(id: string, tags: (current: readonly string[]) => string[]) {
  const queryKey = panelTicketQueryKey(id);
  const previous = queryClient.getQueryData(queryKey);
  if (previous) {
    const next: PanelTicket = { ...previous, tags: tags(previous.tags) };
    queryClient.setQueryData(queryKey, next);
  }
  return { previous };
}

function restoreTags(id: string, previous: PanelTicket | null | undefined) {
  if (previous) {
    queryClient.setQueryData(panelTicketQueryKey(id), previous);
  }
}

function settleTags(id: string, tags: string[]) {
  const queryKey = panelTicketQueryKey(id);
  const current = queryClient.getQueryData(queryKey);
  if (current) {
    const next: PanelTicket = { ...current, tags };
    queryClient.setQueryData(queryKey, next);
  }
  invalidateTicketCollections(id);
}

export function useAddTicketTag() {
  return useMutation({
    mutationFn: async ({ id, tag }: { id: string, tag: string }) => {
      const response = await protoSend(
        'POST',
        `${panelTicketPath(id)}/tags`,
        AddTagRequestSchema,
        create(AddTagRequestSchema, { tag }),
        TicketTagsResponseSchema,
      );
      return response.tags;
    },
    onMutate: async ({ id, tag }) => {
      await queryClient.cancelQueries({ queryKey: panelTicketQueryKey(id) });
      return withTags(id, (current) => (current.includes(tag) ? [...current] : [...current, tag]));
    },
    onError: (_error, { id }, context) => {
      restoreTags(id, context?.previous);
    },
    onSuccess: (tags, { id }) => {
      settleTags(id, tags);
    },
  });
}

export function useRemoveTicketTag() {
  return useMutation({
    mutationFn: async ({ id, tag }: { id: string, tag: string }) => {
      const response = await protoFetch(
        TicketTagsResponseSchema,
        `${panelTicketPath(id)}/tags/${encodeURIComponent(tag)}`,
        { method: 'DELETE' },
      );
      return response.tags;
    },
    onMutate: async ({ id, tag }) => {
      await queryClient.cancelQueries({ queryKey: panelTicketQueryKey(id) });
      return withTags(id, (current) => current.filter((candidate) => candidate !== tag));
    },
    onError: (_error, { id }, context) => {
      restoreTags(id, context?.previous);
    },
    onSuccess: (tags, { id }) => {
      settleTags(id, tags);
    },
  });
}

function refreshWhenAccessExpired(error: Error, { id }: { id: string }) {
  if (error instanceof ProtoHttpError && error.status === 403) {
    void queryClient.invalidateQueries({ queryKey: publicTicketQueryKey(id) });
  }
}

export function useAddTicketReply() {
  return useMutation({
    mutationFn: async ({ id, reply }: { id: string, reply: TicketReplyInput }) => {
      const request = toAddReplyRequest(reply);

      return postPublicRecord(
        'tickets',
        id,
        '/replies',
        toJson(AddReplyRequestSchema, request),
        AddTicketReplyResponseSchema,
      );
    },
    onSuccess: (_data, variables) => {
      void queryClient.invalidateQueries({ queryKey: publicTicketQueryKey(variables.id) });
    },
    onError: refreshWhenAccessExpired,
  });
}

export function useSubmitTicketForm() {
  return useMutation({
    mutationFn: async ({ id, formData }: { id: string, formData: SubmitTicketFormInput }) => {
      const { attachments, ...fields } = formData;
      const request = create(SubmitTicketFormRequestSchema, {
        ...fields,
        attachments: toAttachmentStructs(attachments),
      });

      return postPublicRecord(
        'tickets',
        id,
        '/submit',
        toJson(SubmitTicketFormRequestSchema, request),
        SubmitPublicTicketResponseSchema,
      );
    },
    onSuccess: (_data, variables) => {
      void queryClient.invalidateQueries({ queryKey: publicTicketQueryKey(variables.id) });
    },
    onError: refreshWhenAccessExpired,
  });
}

export function useRequestTicketVerification() {
  return useMutation({
    mutationFn: async (ticketId: string) => requestPublicVerification('tickets', ticketId)
  });
}

export function useVerifyTicketCode() {
  return useMutation({
    mutationFn: async ({ ticketId, code }: { ticketId: string, code: string }) =>
      verifyPublicCode('tickets', ticketId, code),
    onSuccess: async (_data, { ticketId }) => queryClient.invalidateQueries({ queryKey: publicTicketQueryKey(ticketId) }),
  });
}

export function usePanelTicket(id: string) {
  return useQuery({
    ...panelTicketQuery(id),
    enabled: !!id,
    refetchOnWindowFocus: true
  });
}

export function usePlayerAllTickets(uuid: string) {
  return useQuery({
    queryKey: ['/v1/panel/tickets/player', uuid],
    queryFn: async () => {
      const res = await apiFetch(`/v1/panel/tickets/player/${encodeURIComponent(uuid)}`);
      if (res.status === 404) return [];
      if (!res.ok) throw await protoHttpErrorOf(res);
      return parsePlayerTickets(parseJsonOrNull(await res.text()));
    },
    enabled: !!uuid,
    staleTime: 30000,
    refetchOnWindowFocus: true
  });
}

export function useTicketStatusCounts(options?: {
  search?: string;
  types?: string[];
  author?: string;
  labels?: string[];
  assignees?: string[];
}) {
  const { search = '', types = [], author = '', labels = [], assignees = [] } = options ?? {};

  return useQuery({
    queryKey: ['/v1/panel/tickets/counts', { search, types, author, labels, assignees }],
    queryFn: async (): Promise<{ open: number; closed: number }> => {
      const params = new URLSearchParams();
      if (search) params.append('search', search);
      if (types.length > 0) {
        types.forEach(type => params.append('type', type));
      }
      if (author) params.append('author', author);
      if (labels.length > 0) {
        labels.forEach(label => params.append('labels', label));
      }
      if (assignees.length > 0) {
        assignees.forEach(assignee => params.append('assignee', assignee));
      }

      const response = await protoFetch(TicketCountsResponseSchema, `/v1/panel/tickets/counts?${params.toString()}`);
      return { open: Number(response.open), closed: Number(response.closed) };
    },
    staleTime: 30000,
    refetchOnWindowFocus: true,
    placeholderData: keepPreviousData,
  });
}

export function useBulkUpdateTickets() {
  return useMutation({
    mutationFn: async (request: {
      ticketIds: string[];
      locked?: boolean;
      addLabels?: string[];
      removeLabels?: string[];
      assignTo?: string;
    }) =>
      protoSend(
        'POST',
        '/v1/panel/tickets/bulk',
        BulkTicketUpdateRequestSchema,
        create(BulkTicketUpdateRequestSchema, request),
        BulkTicketUpdateResponseSchema,
      ),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['/v1/panel/tickets'] });
      void queryClient.invalidateQueries({ queryKey: ['/v1/panel/tickets/counts'] });
    },
  });
}

type AiSuggestionActionResponse = MessageShape<typeof AISuggestionActionResponseSchema>;

function requireAiSuggestionSuccess(response: AiSuggestionActionResponse): AiSuggestionActionResponse {
  if (!response.success) {
    throw new Error(response.error);
  }
  return response;
}

export function useApplyTicketAiSuggestion() {
  return useMutation({
    mutationFn: async ({ ticketId, staffName }: { ticketId: string, staffName: string | undefined, reportedPlayerUuid: string }) =>
      requireAiSuggestionSuccess(await protoSend(
        'POST',
        `/v1/panel/settings/ai-apply-punishment/${encodeURIComponent(ticketId)}`,
        ApplyAIPunishmentRequestSchema,
        create(ApplyAIPunishmentRequestSchema, { staffName }),
        AISuggestionActionResponseSchema,
      )),
    onSuccess: async (_data, { ticketId, reportedPlayerUuid }) => {
      invalidateTicketCollections(ticketId);
      if (reportedPlayerUuid) {
        void queryClient.invalidateQueries({ queryKey: ['/v1/panel/players', reportedPlayerUuid] });
      }
      await queryClient.invalidateQueries({ queryKey: panelTicketQueryKey(ticketId) });
    },
  });
}

export function useDismissTicketAiSuggestion() {
  return useMutation({
    mutationFn: async (ticketId: string) =>
      requireAiSuggestionSuccess(await protoFetch(
        AISuggestionActionResponseSchema,
        `/v1/panel/settings/ai-dismiss-suggestion/${encodeURIComponent(ticketId)}`,
        { method: 'POST' },
      )),
    onSuccess: async (_data, ticketId) => queryClient.invalidateQueries({ queryKey: panelTicketQueryKey(ticketId) }),
  });
}
