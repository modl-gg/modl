import { keepPreviousData, queryOptions, useQuery, useMutation } from '@tanstack/react-query';
import { create, type JsonObject } from '@bufbuild/protobuf';
import { z } from 'zod';
import { queryClient } from '../../lib/queryClient';
import { useDebouncedValue } from '../use-debounced-value';
import { apiFetch } from '@/lib/api';
import i18n from '@/lib/i18n';
import { protoFetch, protoSend, protoFetchOrNull } from '@/lib/proto-fetch';
import { SimpleResponseSchema } from '@proto/modl/v1/common_pb.ts';
import {
  PanelCreatePunishmentRequestSchema,
  PanelAddModificationRequestSchema,
  PanelAddPunishmentNoteRequestSchema,
  PanelAddEvidenceRequestSchema,
  ModifyPunishmentTicketsRequestSchema,
  PunishmentResponseSchema,
  PunishmentPreviewResponseSchema,
  type PanelCreatePunishmentRequest,
} from '@proto/modl/v1/punishment_pb.ts';
import { mapPunishment } from '@/lib/punishment-mapping';
import { definedJsonFields } from '@/lib/proto-ui';
import { isCustomPunishmentOrdinal } from '@/lib/manual-punishments';
import type { OffenseLevel } from '@/lib/punishment-preview';
import {
  durationToMilliseconds,
  type PunishmentApplicationData,
  type PunishmentApplicationRequest,
  type PunishmentFormDuration,
} from '@/lib/punishment-request';
import {
  PanelLinkedBansResponseSchema,
} from '@proto/modl/v1/player_pb.ts';
import {
  PanelPunishmentTypesResponseSchema,
} from '@proto/modl/v1/settings_pb.ts';
import type { QueryGate } from './query-gate';

const ACTIVE_PUNISHMENT_SEARCH_MIN_LENGTH = 2;
const ACTIVE_PUNISHMENT_SEARCH_DEBOUNCE_MS = 300;

const activePunishmentSearchResultsSchema = z.array(z.object({
  id: z.string(),
  playerName: z.string().nullish(),
  status: z.string().nullish(),
  typeOrdinal: z.number().nullish(),
}));

export type ActivePunishmentSearchResult = z.infer<typeof activePunishmentSearchResultsSchema>[number];

const NO_ACTIVE_PUNISHMENTS: readonly ActivePunishmentSearchResult[] = [];

function invalidatePunishmentQueries(playerUuid: string, punishmentId: string): void {
  void queryClient.invalidateQueries({ queryKey: ['/v1/panel/players', playerUuid] });
  void queryClient.invalidateQueries({ queryKey: ['/v1/panel/players/punishments', punishmentId] });
}

export function usePunishmentPreview(uuid: string | undefined, typeOrdinal: number | undefined, offenseLevel?: OffenseLevel) {
  return useQuery({
    queryKey: ['/v1/panel/players', uuid, 'punishment-preview', typeOrdinal, offenseLevel ?? null],
    queryFn: async () => {
      if (uuid === undefined || typeOrdinal === undefined) return null;
      const query = new URLSearchParams({ typeOrdinal: String(typeOrdinal) });
      if (offenseLevel !== undefined) query.set('offenseLevel', offenseLevel);
      return protoFetchOrNull(
        PunishmentPreviewResponseSchema,
        `/v1/panel/players/${encodeURIComponent(uuid)}/punishments/preview?${query.toString()}`,
      );
    },
    enabled: uuid !== undefined && uuid !== '' && typeOrdinal !== undefined && isCustomPunishmentOrdinal(typeOrdinal),
    staleTime: 15_000,
  });
}

function punishmentDataJson(data: PunishmentApplicationData): JsonObject {
  return definedJsonFields({
    silent: data.silent,
    altBlocking: data.altBlocking,
    wipeAfterExpiry: data.wipeAfterExpiry,
    banLinkedAccounts: data.banLinkedAccounts,
    kickSameIP: data.kickSameIP,
    linkedBanId: data.linkedBanId,
  });
}

function createPunishmentRequest(request: PunishmentApplicationRequest): PanelCreatePunishmentRequest {
  return create(PanelCreatePunishmentRequestSchema, {
    typeOrdinal: request.typeOrdinal,
    reason: request.reason,
    duration: request.duration === undefined ? undefined : BigInt(request.duration),
    severity: request.severity,
    status: request.status,
    notes: request.notes.map((note) => ({ text: note.text })),
    evidence: request.evidence.map((item) => ({
      text: item.text,
      type: item.type,
      fileUrl: item.fileUrl,
      fileName: item.fileName,
      fileType: item.fileType,
      fileSize: item.fileSize === undefined ? undefined : BigInt(item.fileSize),
    })),
    attachedTicketIds: [...request.attachedTicketIds],
    data: punishmentDataJson(request.data),
  });
}

export function useApplyPunishment() {
  return useMutation({
    mutationFn: async ({ uuid, request }: { uuid: string; request: PunishmentApplicationRequest }) => {
      return protoSend(
        'POST',
        `/v1/panel/players/${encodeURIComponent(uuid)}/punishments`,
        PanelCreatePunishmentRequestSchema,
        createPunishmentRequest(request),
        SimpleResponseSchema,
      );
    },
    onSuccess: (_data, variables) => {
      void queryClient.invalidateQueries({ queryKey: ['/v1/panel/players', variables.uuid] });
      void queryClient.invalidateQueries({ queryKey: ['/v1/panel/tickets'] });
      void queryClient.invalidateQueries({ queryKey: ['/v1/panel/tickets/player', variables.uuid] });
    }
  });
}

function finiteDurationMillis(duration: PunishmentFormDuration): bigint {
  const millis = durationToMilliseconds(duration);
  if (millis === null) {
    throw new Error(i18n.t('punishment.invalidDurationDesc'));
  }
  return BigInt(Math.trunc(millis));
}

export function useModifyPunishment() {
  return useMutation({
    mutationFn: async ({
      uuid,
      punishmentId,
      modificationType,
      reason,
      newDuration,
      appealTicketId
    }: {
      uuid: string,
      punishmentId: string,
      modificationType: string,
      reason: string,
      newDuration?: { value: number; unit: string } | undefined,
      appealTicketId?: string | undefined
    }) => {
      const request = create(PanelAddModificationRequestSchema, {
        type: modificationType,
        reason
      });

      if (appealTicketId) {
        request.appealTicketId = appealTicketId;
      }

      if ((modificationType === 'MANUAL_DURATION_CHANGE' || modificationType === 'APPEAL_DURATION_CHANGE') && newDuration) {
        request.effectiveDuration = finiteDurationMillis(newDuration);
      }

      return protoSend(
        'POST',
        `/v1/panel/players/${encodeURIComponent(uuid)}/punishments/${encodeURIComponent(punishmentId)}/modifications`,
        PanelAddModificationRequestSchema,
        request,
        SimpleResponseSchema,
      );
    },
    onSuccess: (_data, variables) => {
      invalidatePunishmentQueries(variables.uuid, variables.punishmentId);
    }
  });
}

export function useAddPunishmentNote() {
  return useMutation({
    mutationFn: async ({
      uuid,
      punishmentId,
      noteText
    }: {
      uuid: string,
      punishmentId: string,
      noteText: string
    }) => {
      const request = create(PanelAddPunishmentNoteRequestSchema, {
        text: noteText,
      });

      return protoSend(
        'POST',
        `/v1/panel/players/${encodeURIComponent(uuid)}/punishments/${encodeURIComponent(punishmentId)}/notes`,
        PanelAddPunishmentNoteRequestSchema,
        request,
        SimpleResponseSchema,
      );
    },
    onSuccess: (_data, variables) => {
      invalidatePunishmentQueries(variables.uuid, variables.punishmentId);
    }
  });
}

export interface PunishmentEvidenceSubmission {
  readonly type: 'file' | 'url' | 'text';
  readonly text: string;
  readonly url?: string | undefined;
  readonly fileName?: string | undefined;
  readonly fileType?: string | undefined;
  readonly fileSize?: number | undefined;
}

export function useAddPunishmentEvidence() {
  return useMutation({
    mutationFn: async ({
      uuid,
      punishmentId,
      evidence
    }: {
      uuid: string,
      punishmentId: string,
      evidence: PunishmentEvidenceSubmission
    }) => {
      const request = create(PanelAddEvidenceRequestSchema, {
        text: evidence.text,
        type: evidence.type,
        url: evidence.url,
        fileName: evidence.fileName,
        fileType: evidence.fileType,
        fileSize: evidence.fileSize === undefined ? undefined : BigInt(evidence.fileSize),
      });

      return protoSend(
        'POST',
        `/v1/panel/players/${encodeURIComponent(uuid)}/punishments/${encodeURIComponent(punishmentId)}/evidence`,
        PanelAddEvidenceRequestSchema,
        request,
        SimpleResponseSchema,
      );
    },
    onSuccess: (_data, variables) => {
      invalidatePunishmentQueries(variables.uuid, variables.punishmentId);
    }
  });
}

export function useModifyPunishmentTickets() {
  return useMutation({
    mutationFn: async ({
      uuid,
      punishmentId,
      addTicketIds,
      removeTicketIds,
      modifyAssociatedTickets
    }: {
      uuid: string,
      punishmentId: string,
      addTicketIds?: string[],
      removeTicketIds?: string[],
      modifyAssociatedTickets: boolean
    }) => {
      const request = create(ModifyPunishmentTicketsRequestSchema, {
        punishmentId,
        addTicketIds: addTicketIds ?? [],
        removeTicketIds: removeTicketIds ?? [],
        modifyAssociatedTickets
      });

      return protoSend(
        'POST',
        `/v1/panel/players/${encodeURIComponent(uuid)}/punishments/${encodeURIComponent(punishmentId)}/tickets`,
        ModifyPunishmentTicketsRequestSchema,
        request,
        SimpleResponseSchema,
      );
    },
    onSuccess: (_data, variables) => {
      invalidatePunishmentQueries(variables.uuid, variables.punishmentId);
      void queryClient.invalidateQueries({ queryKey: ['/v1/panel/tickets'] });
      void queryClient.invalidateQueries({ queryKey: ['/v1/panel/tickets/player', variables.uuid] });
    }
  });
}

async function searchActivePunishments(query: string): Promise<ActivePunishmentSearchResult[]> {
  const response = await apiFetch(`/v1/panel/players/punishments/search?q=${encodeURIComponent(query)}&activeOnly=true`);
  if (!response.ok) {
    return [];
  }
  const results = activePunishmentSearchResultsSchema.safeParse(await response.json());
  return results.success ? results.data : [];
}

export function useActivePunishmentSearch(query: string): readonly ActivePunishmentSearchResult[] {
  const trimmedQuery = query.trim();
  const debouncedQuery = useDebouncedValue(trimmedQuery, ACTIVE_PUNISHMENT_SEARCH_DEBOUNCE_MS);
  const enabled = trimmedQuery.length >= ACTIVE_PUNISHMENT_SEARCH_MIN_LENGTH
    && debouncedQuery.length >= ACTIVE_PUNISHMENT_SEARCH_MIN_LENGTH;
  const { data } = useQuery({
    queryKey: ['/v1/panel/players/punishments', 'search', debouncedQuery],
    queryFn: async () => searchActivePunishments(debouncedQuery),
    enabled,
    placeholderData: keepPreviousData,
  });
  return enabled ? data ?? NO_ACTIVE_PUNISHMENTS : NO_ACTIVE_PUNISHMENTS;
}

export function usePunishmentById(punishmentId: string | null) {
  return useQuery({
    queryKey: ['/v1/panel/players/punishments', punishmentId],
    queryFn: async () => {
      if (!punishmentId) return null;
      const response = await protoFetchOrNull(
        PunishmentResponseSchema,
        `/v1/panel/players/punishments/${punishmentId}`,
      );
      return response ? mapPunishment(response) : null;
    },
    enabled: !!punishmentId,
    staleTime: 30000,
  });
}

export function useLinkedBansForPunishment(punishmentId: string | null) {
  return useQuery({
    queryKey: ['/v1/panel/players/punishments', punishmentId, 'linked-bans'],
    queryFn: async () => {
      if (!punishmentId) return [];
      const response = await protoFetchOrNull(
        PanelLinkedBansResponseSchema,
        `/v1/panel/players/punishments/${encodeURIComponent(punishmentId)}/linked-bans`,
      );
      return response?.linkedBans ?? [];
    },
    enabled: !!punishmentId,
    staleTime: 30000,
  });
}

export function punishmentTypesQuery() {
  return queryOptions({
    queryKey: ['/v1/panel/settings/punishment-types'],
    queryFn: async () => {
      const response = await protoFetch(
        PanelPunishmentTypesResponseSchema,
        '/v1/panel/settings/punishment-types',
      );
      return response.punishmentTypes;
    },
    staleTime: 1000 * 60 * 5,
  });
}

export function usePunishmentTypes({ enabled = true }: QueryGate = {}) {
  return useQuery({ ...punishmentTypesQuery(), enabled, refetchOnWindowFocus: false });
}
