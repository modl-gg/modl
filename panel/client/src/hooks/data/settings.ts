import { useCallback } from 'react';
import { queryOptions, useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { create, type Message, type MessageInitShape } from '@bufbuild/protobuf';
import { z } from 'zod';
import type { GenMessage } from '@bufbuild/protobuf/codegenv2';
import {
  DataRetentionSettingsEnvelopeSchema,
  GeneralSettingsEnvelopeSchema,
  PatchDataRetentionSettingsRequestSchema,
  PatchGeneralSettingsRequestSchema,
  type TicketFormSchema,
  TicketFormSettingsEnvelopeSchema,
  type TicketFormSettingsSchema,
  QuickResponseSettingsEnvelopeSchema,
  OffenderThresholdSettingsEnvelopeSchema,
  TicketLabelSettingsEnvelopeSchema,
  ReplayRetentionSettingsEnvelopeSchema,
  WebhookSettingsSchema,
  PublicSettingsResponseSchema,
  ApiKeyExistsResponseSchema,
  ApiKeyGenerateResponseSchema,
  ApiKeyRevealResponseSchema,
  MailSettingsResponseSchema,
  MailSource,
  MailTestOutcome,
  TestMailRequestSchema,
  TestMailResponseSchema,
  UpdateMailSettingsRequestSchema,
  AIModerationSettingsSchema,
  PatchQuickResponsesRequestSchema,
  PatchStatusThresholdSettingsRequestSchema,
  PatchTicketFormSettingsRequestSchema,
  PatchTicketLabelSettingsRequestSchema,
  UpdateAIModerationSettingsRequestSchema,
  UpdateWebhookSettingsRequestSchema,
  WebhookTestResponseSchema,
  type AIModerationSettings,
  type CategoryThresholds,
  type FormField,
  type FormSection,
  type GeneralSettingsEnvelope,
  type MailSettingsResponse,
  type OffenderThresholdSettings,
  type OffenderThresholdSettingsEnvelope,
  type QuickResponseSettings,
  type QuickResponseSettingsEnvelope,
  type TicketFormSettingsEnvelope,
  type TicketLabelSettingsEnvelope,
  type SettingsMeta,
  type GeneralSettings,
  type TicketForm,
  type TicketFormSettings as TicketFormSettingsMessage,
  type TicketLabelSettings,
  type WebhookEmbedTemplate,
  type WebhookSettings,
} from '@proto/modl/v1/settings_pb.ts';
import type { TicketFormField, TicketFormSection, TicketFormSettings, TicketFormsConfiguration } from '@/types/forms';
import type { QuickResponseAction, QuickResponseCategory, QuickResponsesConfiguration } from '@/types/quickResponses';
import { apiFetch } from '@/lib/api';
import { ApiHttpError, apiErrorCode, isConflictError, parseJsonOrNull, serverErrorMessage } from '@/lib/http-error';
import { decodeProtoResponse, protoFetch, protoSend, ProtoHttpError } from '@/lib/proto-fetch';
import { definedFields, optionalField } from '@/lib/defined-fields';
import { tsToDate } from '@/lib/proto-ui';
import { isPublicPage } from '@/utils/routes';
import { PUBLIC_SETTINGS_PATH, PUBLIC_SETTINGS_QUERY_KEY } from '@/lib/public-queries';
import type { QueryGate } from './query-gate';

interface SettingsMetaShape {
  version: number;
  updatedAt: string | null;
}

interface SettingsEnvelope<T> {
  data: T;
  _meta: SettingsMetaShape;
}

function metaToShape(meta?: SettingsMeta): SettingsMetaShape {
  if (!meta) {
    return { version: 0, updatedAt: null };
  }
  const updatedAt = tsToDate(meta.updatedAt);
  return {
    version: Number(meta.version),
    updatedAt: updatedAt ? updatedAt.toISOString() : null,
  };
}

type SettingsEnvelopeMessage = Message & { data?: object | undefined; meta?: SettingsMeta | undefined };

type DecodedSettingsEnvelope<Envelope extends SettingsEnvelopeMessage> = SettingsEnvelope<NonNullable<Envelope['data']> | null>;

function toSettingsEnvelope<Envelope extends SettingsEnvelopeMessage>(decoded: Envelope): DecodedSettingsEnvelope<Envelope> {
  return {
    data: decoded.data ?? null,
    _meta: metaToShape(decoded.meta),
  };
}

async function fetchSettingsEnvelope<Envelope extends SettingsEnvelopeMessage>(
  schema: GenMessage<Envelope>,
  path: string,
): Promise<DecodedSettingsEnvelope<Envelope> | null> {
  let decoded: Envelope;
  try {
    decoded = await protoFetch(schema, path);
  } catch (error) {
    if (error instanceof ProtoHttpError && (error.status === 401 || error.status === 403)) {
      return null;
    }
    throw error;
  }
  return toSettingsEnvelope(decoded);
}

export interface ReplayRetentionSettings {
  enabled: boolean;
  days: number;
}

export function useSettings({ enabled = true }: QueryGate = {}) {
  return useQuery({
    queryKey: ['/v1/settings'],
    enabled,
    queryFn: async () => {
      const isPublic = isPublicPage();

      const fetchPublic = async () => {
        const publicData = await protoFetch(PublicSettingsResponseSchema, PUBLIC_SETTINGS_PATH);
        return {
          settings: {
            general: {
              serverDisplayName: publicData.serverDisplayName,
              panelIconUrl: publicData.panelIconUrl,
              homepageIconUrl: publicData.homepageIconUrl,
              defaultLanguage: publicData.defaultLanguage
            },
            ticketForms: publicData.ticketForms ?? {}
          }
        };
      };

      try {
        if (isPublic) {
          return await fetchPublic();
        }

        let generalEnvelope: SettingsEnvelope<Partial<GeneralSettings>> | null = null;
        try {
          const decoded = await protoFetch(GeneralSettingsEnvelopeSchema, '/v1/panel/settings/general');
          generalEnvelope = {
            data: decoded.data ?? {},
            _meta: metaToShape(decoded.meta),
          };
        } catch (error) {
          if (error instanceof ProtoHttpError && error.status === 401) {
            return await fetchPublic();
          }
          throw error;
        }

        let webhookSettings: WebhookSettings | null = null;
        try {
          webhookSettings = await protoFetch(WebhookSettingsSchema, '/v1/panel/settings/webhooks');
        } catch {
          webhookSettings = null;
        }

        const general = generalEnvelope.data;
        return {
          settings: {
            ...general,
            general,
            generalMeta: generalEnvelope._meta,
            webhookSettings
          }
        };
      } catch {
        try {
          if (isPublic) {
            const decoded = await protoFetch(GeneralSettingsEnvelopeSchema, '/v1/panel/settings/general');
            const general = decoded.data ?? {};
            return {
              settings: {
                ...general,
                general,
                generalMeta: metaToShape(decoded.meta),
              }
            };
          }
          return await fetchPublic();
        } catch {
          return {
            settings: {
              general: {
                serverDisplayName: 'modl',
                panelIconUrl: null,
                homepageIconUrl: null
              },
              ticketForms: {}
            }
          };
        }
      }
    },
    staleTime: 300000,
    refetchOnWindowFocus: false,
    gcTime: 1000 * 60 * 5,
    refetchInterval: false,
    refetchOnReconnect: false
  });
}

const TICKET_FORMS_PATH = '/v1/panel/settings/ticket-forms';
const QUICK_RESPONSES_PATH = '/v1/panel/settings/quick-responses';
const STATUS_THRESHOLDS_PATH = '/v1/panel/settings/status-thresholds';
const TICKET_LABELS_PATH = '/v1/panel/settings/ticket-labels';
const SECTION_SETTINGS_STALE_MS = 1000 * 60 * 5;

const ticketFormSettingsQuery = () => queryOptions({
  queryKey: [TICKET_FORMS_PATH],
  queryFn: async () => fetchSettingsEnvelope(TicketFormSettingsEnvelopeSchema, TICKET_FORMS_PATH),
  staleTime: SECTION_SETTINGS_STALE_MS,
  refetchOnWindowFocus: false,
});

const quickResponseSettingsQuery = () => queryOptions({
  queryKey: [QUICK_RESPONSES_PATH],
  queryFn: async () => fetchSettingsEnvelope(QuickResponseSettingsEnvelopeSchema, QUICK_RESPONSES_PATH),
  staleTime: SECTION_SETTINGS_STALE_MS,
  refetchOnWindowFocus: false,
});

const statusThresholdSettingsQuery = () => queryOptions({
  queryKey: [STATUS_THRESHOLDS_PATH],
  queryFn: async () => fetchSettingsEnvelope(OffenderThresholdSettingsEnvelopeSchema, STATUS_THRESHOLDS_PATH),
  staleTime: SECTION_SETTINGS_STALE_MS,
  refetchOnWindowFocus: false,
});

const ticketLabelSettingsQuery = () => queryOptions({
  queryKey: [TICKET_LABELS_PATH],
  queryFn: async () => fetchSettingsEnvelope(TicketLabelSettingsEnvelopeSchema, TICKET_LABELS_PATH),
  staleTime: SECTION_SETTINGS_STALE_MS,
  refetchOnWindowFocus: false,
});

export function useTicketFormSettings({ enabled = true }: QueryGate = {}) {
  return useQuery({ ...ticketFormSettingsQuery(), enabled });
}

export function useQuickResponses({ enabled = true }: QueryGate = {}) {
  return useQuery({ ...quickResponseSettingsQuery(), enabled });
}

export function useStatusThresholds({ enabled = true }: QueryGate = {}) {
  return useQuery({ ...statusThresholdSettingsQuery(), enabled });
}

export function useTicketLabelSettings({ enabled = true }: QueryGate = {}) {
  return useQuery({ ...ticketLabelSettingsQuery(), enabled });
}

export interface VersionedSettings<T> {
  value: T;
  version: number;
}

export interface SettingsWrite<T> {
  expectedVersion: number;
  value: T;
}

export interface SettingsSaveResult<T> {
  saved: VersionedSettings<T>;
}

function versioned<Data, Value>(
  envelope: SettingsEnvelope<Data | null> | null,
  toValue: (data: Data | null) => Value,
): VersionedSettings<Value> | null {
  if (envelope === null) return null;
  return { value: toValue(envelope.data), version: envelope._meta.version };
}

export interface TicketFormsDocument extends TicketFormsConfiguration {
  player?: TicketFormSettings;
  chat?: TicketFormSettings;
}

const TICKET_FORM_FIELD_TYPES = [
  'text', 'textarea', 'dropdown', 'multiple_choice', 'checkbox', 'file_upload', 'checkboxes', 'description',
] as const satisfies readonly TicketFormField['type'][];

function toTicketFormFieldType(type: string): TicketFormField['type'] {
  return TICKET_FORM_FIELD_TYPES.find((candidate) => candidate === type) ?? 'text';
}

function toTicketFormField(field: FormField): TicketFormField {
  return {
    id: field.id,
    type: toTicketFormFieldType(field.type),
    label: field.label,
    description: field.description,
    required: field.required,
    options: [...field.options],
    order: field.order,
    sectionId: field.sectionId,
    goToSection: field.goToSection,
    optionSectionMapping: { ...field.optionSectionMapping },
  };
}

function toTicketFormSection(section: FormSection): TicketFormSection {
  return {
    id: section.id,
    title: section.title,
    description: section.description,
    order: section.order,
    showIfFieldId: section.showIfFieldId,
    showIfValue: section.showIfValue,
    showIfValues: [...section.showIfValues],
    hideByDefault: section.hideByDefault,
  };
}

function toTicketFormSettings(form: TicketForm | undefined): TicketFormSettings {
  return {
    requireEmail: form?.requireEmail ?? false,
    requireEmailAuth: form?.requireEmailAuth ?? false,
    allowEmailNotifications: form?.allowEmailNotifications ?? false,
    fields: form?.fields.map(toTicketFormField) ?? [],
    sections: form?.sections.map(toTicketFormSection) ?? [],
  };
}

function toOptionalTicketFormSettings(form: TicketForm | undefined): TicketFormSettings | undefined {
  return form === undefined ? undefined : toTicketFormSettings(form);
}

function toTicketFormsDocument(settings: TicketFormSettingsMessage | null): TicketFormsDocument {
  return {
    bug: toTicketFormSettings(settings?.bug),
    support: toTicketFormSettings(settings?.support),
    application: toTicketFormSettings(settings?.application),
    ...optionalField('player', toOptionalTicketFormSettings(settings?.player)),
    ...optionalField('chat', toOptionalTicketFormSettings(settings?.chat)),
  };
}

function toTicketFormInit(form: TicketFormSettings): MessageInitShape<typeof TicketFormSchema> {
  return { ...form, fields: form.fields.map(definedFields), sections: form.sections.map(definedFields) };
}

function toTicketFormsInit(document: TicketFormsDocument): MessageInitShape<typeof TicketFormSettingsSchema> {
  return {
    bug: toTicketFormInit(document.bug),
    support: toTicketFormInit(document.support),
    application: toTicketFormInit(document.application),
    player: document.player && toTicketFormInit(document.player),
    chat: document.chat && toTicketFormInit(document.chat),
  };
}

type QuickResponseCategoryMessage = QuickResponseSettings['categories'][number];
type QuickResponseActionMessage = QuickResponseCategoryMessage['actions'][number];

const APPEAL_ACTIONS = ['pardon', 'reduce', 'reject', 'none'] as const satisfies readonly NonNullable<QuickResponseAction['appealAction']>[];

function toQuickResponseAction(action: QuickResponseActionMessage, index: number): QuickResponseAction {
  return {
    id: action.id,
    name: action.name,
    message: action.message,
    order: action.order ?? index,
    ...optionalField('closeTicket', action.closeTicket),
    ...optionalField('showPunishment', action.showPunishment),
    ...optionalField('appealAction', APPEAL_ACTIONS.find((candidate) => candidate === action.appealAction)),
  };
}

function toQuickResponseCategory(category: QuickResponseCategoryMessage, index: number): QuickResponseCategory {
  return {
    id: category.id,
    name: category.name,
    ticketTypes: [...category.ticketTypes],
    actions: category.actions.map(toQuickResponseAction),
    order: category.order ?? index,
  };
}

function toQuickResponsesConfiguration(settings: QuickResponseSettings | null): QuickResponsesConfiguration {
  return { categories: settings?.categories.map(toQuickResponseCategory) ?? [] };
}

export interface CategoryStatusThresholds {
  medium: number;
  habitual: number;
  pointExpiryMonths: number;
}

export interface StatusThresholds {
  gameplay: CategoryStatusThresholds;
  social: CategoryStatusThresholds;
}

export const DEFAULT_STATUS_THRESHOLDS: StatusThresholds = {
  gameplay: { medium: 5, habitual: 10, pointExpiryMonths: 24 },
  social: { medium: 4, habitual: 8, pointExpiryMonths: 24 },
};

function toCategoryStatusThresholds(thresholds: CategoryThresholds): CategoryStatusThresholds {
  return { medium: thresholds.medium, habitual: thresholds.habitual, pointExpiryMonths: thresholds.pointExpiryMonths };
}

function toStatusThresholds(settings: OffenderThresholdSettings | null): StatusThresholds {
  if (settings?.gameplay === undefined || settings.social === undefined) return DEFAULT_STATUS_THRESHOLDS;
  return { gameplay: toCategoryStatusThresholds(settings.gameplay), social: toCategoryStatusThresholds(settings.social) };
}

export interface TicketLabel {
  id: string;
  name: string;
  color: string;
  description?: string;
}

function toTicketLabels(settings: TicketLabelSettings | null): TicketLabel[] {
  return settings?.labels.map((label) => ({ id: label.id, name: label.name, color: label.color, description: label.description })) ?? [];
}

const selectTicketFormsDocument = (envelope: DecodedSettingsEnvelope<TicketFormSettingsEnvelope> | null) =>
  versioned(envelope, toTicketFormsDocument);
const selectQuickResponsesConfiguration = (envelope: DecodedSettingsEnvelope<QuickResponseSettingsEnvelope> | null) =>
  versioned(envelope, toQuickResponsesConfiguration);
const selectStatusThresholds = (envelope: DecodedSettingsEnvelope<OffenderThresholdSettingsEnvelope> | null) =>
  versioned(envelope, toStatusThresholds);
const selectTicketLabels = (envelope: DecodedSettingsEnvelope<TicketLabelSettingsEnvelope> | null) =>
  versioned(envelope, toTicketLabels);

export function useTicketFormsDocument({ enabled = true }: QueryGate = {}) {
  return useQuery({ ...ticketFormSettingsQuery(), enabled, select: selectTicketFormsDocument });
}

export function useQuickResponsesConfiguration({ enabled = true }: QueryGate = {}) {
  return useQuery({ ...quickResponseSettingsQuery(), enabled, select: selectQuickResponsesConfiguration });
}

export function useStatusThresholdSettings({ enabled = true }: QueryGate = {}) {
  return useQuery({ ...statusThresholdSettingsQuery(), enabled, select: selectStatusThresholds });
}

export function useTicketLabels({ enabled = true }: QueryGate = {}) {
  return useQuery({ ...ticketLabelSettingsQuery(), enabled, select: selectTicketLabels });
}

function isStaleOrForbiddenWrite(error: unknown): boolean {
  return isConflictError(error) || (error instanceof ApiHttpError && error.status === 403);
}

function useSectionSettingsMutation<Envelope extends SettingsEnvelopeMessage, Value>(
  path: string,
  send: (write: SettingsWrite<Value>) => Promise<Envelope>,
  toValue: (data: NonNullable<Envelope['data']> | null) => Value,
  dependentQueryKeys: readonly (readonly string[])[] = [],
) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (write: SettingsWrite<Value>) => {
      const envelope = toSettingsEnvelope(await send(write));
      return { envelope, saved: { value: toValue(envelope.data), version: envelope._meta.version } };
    },
    onSuccess: async ({ envelope }) => {
      queryClient.setQueryData([path], envelope);
      await Promise.all(dependentQueryKeys.map(async (queryKey) => queryClient.invalidateQueries({ queryKey })));
    },
    onError: async (error) => {
      if (isStaleOrForbiddenWrite(error)) {
        await queryClient.invalidateQueries({ queryKey: [path] });
      }
    },
  });
}

export function usePatchTicketForms() {
  return useSectionSettingsMutation(TICKET_FORMS_PATH, async ({ expectedVersion, value }: SettingsWrite<TicketFormsDocument>) => protoSend(
    'PATCH',
    TICKET_FORMS_PATH,
    PatchTicketFormSettingsRequestSchema,
    create(PatchTicketFormSettingsRequestSchema, { expectedVersion: BigInt(expectedVersion), settings: toTicketFormsInit(value) }),
    TicketFormSettingsEnvelopeSchema,
  ), toTicketFormsDocument);
}

export function usePatchQuickResponses() {
  return useSectionSettingsMutation(QUICK_RESPONSES_PATH, async ({ expectedVersion, value }: SettingsWrite<QuickResponsesConfiguration>) => protoSend(
    'PATCH',
    QUICK_RESPONSES_PATH,
    PatchQuickResponsesRequestSchema,
    create(PatchQuickResponsesRequestSchema, { expectedVersion: BigInt(expectedVersion), categories: value.categories }),
    QuickResponseSettingsEnvelopeSchema,
  ), toQuickResponsesConfiguration);
}

export function usePatchStatusThresholds() {
  return useSectionSettingsMutation(STATUS_THRESHOLDS_PATH, async ({ expectedVersion, value }: SettingsWrite<StatusThresholds>) => protoSend(
    'PATCH',
    STATUS_THRESHOLDS_PATH,
    PatchStatusThresholdSettingsRequestSchema,
    create(PatchStatusThresholdSettingsRequestSchema, { expectedVersion: BigInt(expectedVersion), settings: value }),
    OffenderThresholdSettingsEnvelopeSchema,
  ), toStatusThresholds);
}

const TICKET_LABEL_DEPENDENT_QUERY_KEYS = [[TICKET_LABELS_PATH, 'labels']] as const;

export function usePatchTicketLabels() {
  return useSectionSettingsMutation(TICKET_LABELS_PATH, async ({ expectedVersion, value }: SettingsWrite<TicketLabel[]>) => protoSend(
    'PATCH',
    TICKET_LABELS_PATH,
    PatchTicketLabelSettingsRequestSchema,
    create(PatchTicketLabelSettingsRequestSchema, { expectedVersion: BigInt(expectedVersion), labels: value }),
    TicketLabelSettingsEnvelopeSchema,
  ), toTicketLabels, TICKET_LABEL_DEPENDENT_QUERY_KEYS);
}

const AI_MODERATION_PATH = '/v1/panel/settings/ai-moderation';
const AI_MODERATION_QUERY_KEY = [AI_MODERATION_PATH] as const;

export interface AiPunishmentConfig {
  id: string;
  name: string;
  aiDescription: string;
  enabled: boolean;
}

export interface AiModerationConfig {
  enableAIReview: boolean;
  enableAutomatedActions: boolean;
  aiPunishmentConfigs: Record<string, AiPunishmentConfig>;
}

function toAiModerationConfig(settings: AIModerationSettings): AiModerationConfig {
  return {
    enableAIReview: settings.enableAiReview,
    enableAutomatedActions: settings.enableAutomatedActions,
    aiPunishmentConfigs: Object.fromEntries(Object.entries(settings.aiPunishmentConfigs).map(([key, config]) => [
      key,
      { id: config.id, name: config.name, aiDescription: config.aiDescription, enabled: config.enabled },
    ])),
  };
}

function toVersionedAiModeration(settings: AIModerationSettings): VersionedSettings<AiModerationConfig> {
  return { value: toAiModerationConfig(settings), version: Number(settings.version) };
}

export function useAiModerationSettings({ enabled = true }: QueryGate = {}) {
  return useQuery({
    queryKey: AI_MODERATION_QUERY_KEY,
    enabled,
    queryFn: async () => toVersionedAiModeration(await protoFetch(AIModerationSettingsSchema, AI_MODERATION_PATH)),
    staleTime: SECTION_SETTINGS_STALE_MS,
    refetchOnWindowFocus: false,
  });
}

export function useSaveAiModerationSettings() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ expectedVersion, value }: SettingsWrite<AiModerationConfig>): Promise<SettingsSaveResult<AiModerationConfig>> => ({
      saved: toVersionedAiModeration(await protoSend(
        'PATCH',
        AI_MODERATION_PATH,
        UpdateAIModerationSettingsRequestSchema,
        create(UpdateAIModerationSettingsRequestSchema, {
          expectedVersion: BigInt(expectedVersion),
          enableAiReview: value.enableAIReview,
          enableAutomatedActions: value.enableAutomatedActions,
          aiPunishmentConfigs: value.aiPunishmentConfigs,
        }),
        AIModerationSettingsSchema,
      )),
    }),
    onSuccess: ({ saved }) => queryClient.setQueryData(AI_MODERATION_QUERY_KEY, saved),
    onError: async (error) => {
      if (isStaleOrForbiddenWrite(error)) {
        await queryClient.invalidateQueries({ queryKey: AI_MODERATION_QUERY_KEY });
      }
    },
  });
}

export interface WebhookEmbedFieldConfig {
  name: string;
  value: string;
  inline: boolean;
}

export interface WebhookEmbedTemplateConfig {
  title: string;
  description: string;
  color: string;
  fields: WebhookEmbedFieldConfig[];
}

export interface WebhookNotificationConfig {
  newTickets: boolean;
  newPunishments: boolean;
  auditLogs: boolean;
}

export interface WebhookEmbedTemplatesConfig {
  newTickets: WebhookEmbedTemplateConfig;
  newPunishments: WebhookEmbedTemplateConfig;
  auditLogs: WebhookEmbedTemplateConfig;
}

export const WEBHOOK_DELIVERY_EVENTS = ['newTickets', 'newPunishments', 'auditLogs', 'test'] as const;
export type WebhookDeliveryEvent = typeof WEBHOOK_DELIVERY_EVENTS[number];

export const WEBHOOK_FAILURE_REASONS = ['REJECTED', 'RATE_LIMITED', 'DISCORD_ERROR', 'UNREACHABLE', 'INVALID_URL'] as const;
export type WebhookFailureReason = typeof WEBHOOK_FAILURE_REASONS[number];

export interface WebhookDeliveryFailureInfo {
  failedAt: number;
  event: WebhookDeliveryEvent;
  reason: WebhookFailureReason;
  httpStatus: number;
}

export interface WebhookConfiguration {
  discordWebhookConfigured: boolean;
  discordWebhookUrlSuffix: string;
  replacementWebhookUrl: string | null;
  discordAdminRoleId: string;
  botName: string;
  avatarUrl: string;
  enabled: boolean;
  notifications: WebhookNotificationConfig;
  embedTemplates: WebhookEmbedTemplatesConfig;
  lastDeliveryFailure?: WebhookDeliveryFailureInfo;
}

const EMPTY_WEBHOOK_EMBED_TEMPLATE: WebhookEmbedTemplateConfig = { title: '', description: '', color: '#000000', fields: [] };

export const EMPTY_WEBHOOK_EMBED_TEMPLATES: WebhookEmbedTemplatesConfig = {
  newTickets: EMPTY_WEBHOOK_EMBED_TEMPLATE,
  newPunishments: EMPTY_WEBHOOK_EMBED_TEMPLATE,
  auditLogs: EMPTY_WEBHOOK_EMBED_TEMPLATE,
};

function toWebhookDeliveryFailure(settings: WebhookSettings): WebhookDeliveryFailureInfo | undefined {
  const failure = settings.lastDeliveryFailure;
  if (failure === undefined) {
    return undefined;
  }
  const event = WEBHOOK_DELIVERY_EVENTS.find((candidate) => candidate === failure.event);
  const reason = WEBHOOK_FAILURE_REASONS.find((candidate) => candidate === failure.reason);
  if (event === undefined || reason === undefined) {
    return undefined;
  }
  return { failedAt: Number(failure.failedAt), event, reason, httpStatus: failure.httpStatus };
}

function toWebhookEmbedTemplateConfig(template: WebhookEmbedTemplate | undefined): WebhookEmbedTemplateConfig {
  if (template === undefined) return EMPTY_WEBHOOK_EMBED_TEMPLATE;
  return {
    title: template.title,
    description: template.description,
    color: template.color,
    fields: template.fields.map((field) => ({ name: field.name, value: field.value, inline: field.inline })),
  };
}

function toWebhookEmbedTemplatesConfig(settings: WebhookSettings): WebhookEmbedTemplatesConfig {
  const templates = settings.embedTemplates;
  return {
    newTickets: toWebhookEmbedTemplateConfig(templates?.newTickets),
    newPunishments: toWebhookEmbedTemplateConfig(templates?.newPunishments),
    auditLogs: toWebhookEmbedTemplateConfig(templates?.auditLogs),
  };
}

function toWebhookConfiguration(settings: WebhookSettings): WebhookConfiguration {
  return {
    discordWebhookConfigured: settings.discordWebhookConfigured,
    discordWebhookUrlSuffix: settings.discordWebhookUrlSuffix,
    replacementWebhookUrl: null,
    discordAdminRoleId: settings.discordAdminRoleId,
    botName: settings.botName,
    avatarUrl: settings.avatarUrl,
    enabled: settings.enabled,
    notifications: {
      newTickets: settings.notifications?.newTickets ?? false,
      newPunishments: settings.notifications?.newPunishments ?? false,
      auditLogs: settings.notifications?.auditLogs ?? false,
    },
    embedTemplates: toWebhookEmbedTemplatesConfig(settings),
    ...optionalField('lastDeliveryFailure', toWebhookDeliveryFailure(settings)),
  };
}

type SettingsSnapshot = NonNullable<ReturnType<typeof useSettings>['data']>;

function toVersionedWebhookConfiguration(settings: WebhookSettings): VersionedSettings<WebhookConfiguration> {
  return { value: toWebhookConfiguration(settings), version: Number(settings.version) };
}

export function webhookSettingsOf(snapshot: SettingsSnapshot | undefined): VersionedSettings<WebhookConfiguration> | undefined {
  const settings = snapshot?.settings;
  if (settings === undefined || !('webhookSettings' in settings) || settings.webhookSettings === null) {
    return undefined;
  }
  return toVersionedWebhookConfiguration(settings.webhookSettings);
}

function withWebhookSettings(snapshot: SettingsSnapshot | undefined, webhookSettings: WebhookSettings) {
  if (snapshot === undefined || !('webhookSettings' in snapshot.settings)) {
    return snapshot;
  }
  return { settings: { ...snapshot.settings, webhookSettings } };
}

function toUpdateWebhookSettingsRequest(value: WebhookConfiguration, expectedVersion: number) {
  return create(UpdateWebhookSettingsRequestSchema, {
    ...optionalField('discordWebhookUrl', value.replacementWebhookUrl ?? undefined),
    discordAdminRoleId: value.discordAdminRoleId,
    botName: value.botName,
    avatarUrl: value.avatarUrl,
    enabled: value.enabled,
    notifications: value.notifications,
    embedTemplates: value.embedTemplates,
    expectedVersion: BigInt(expectedVersion),
  });
}

export function useSaveWebhookSettings() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ expectedVersion, value }: SettingsWrite<WebhookConfiguration>) => {
      const stored = await protoSend(
        'PATCH',
        '/v1/panel/settings/webhooks',
        UpdateWebhookSettingsRequestSchema,
        toUpdateWebhookSettingsRequest(value, expectedVersion),
        WebhookSettingsSchema,
      );
      return { stored, saved: toVersionedWebhookConfiguration(stored) };
    },
    onSuccess: ({ stored }) => {
      queryClient.setQueryData<SettingsSnapshot>(['/v1/settings'], (snapshot) => withWebhookSettings(snapshot, stored));
    },
    onError: async (error) => {
      if (isStaleOrForbiddenWrite(error)) {
        await queryClient.invalidateQueries({ queryKey: ['/v1/settings'] });
      }
    },
  });
}

export function useTestWebhook() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (): Promise<void> => {
      await protoFetch(WebhookTestResponseSchema, '/v1/panel/settings/webhooks/test', { method: 'POST' });
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: ['/v1/settings'] });
    },
  });
}

export function useReplayRetentionSettings() {
  return useQuery<SettingsEnvelope<ReplayRetentionSettings> | null>({
    queryKey: ['/v1/panel/settings/replay-retention'],
    queryFn: async () => {
      const envelope = await fetchSettingsEnvelope(ReplayRetentionSettingsEnvelopeSchema, '/v1/panel/settings/replay-retention');
      if (!envelope?.data) {
        return null;
      }
      return {
        data: { enabled: envelope.data.enabled, days: envelope.data.days },
        _meta: envelope._meta,
      };
    },
    staleTime: 1000 * 60 * 5,
    refetchOnWindowFocus: false
  });
}

export function useUpdateReplayRetentionSettings() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (settings: ReplayRetentionSettings & { expectedVersion: number }): Promise<SettingsEnvelope<ReplayRetentionSettings>> => {
      const res = await apiFetch('/v1/panel/settings/replay-retention', {
        method: 'PATCH',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify(settings)
      });

      if (!res.ok) {
        throw new ApiHttpError(res.status, res.statusText, await res.text().catch(() => ''));
      }

      const decoded = await decodeProtoResponse(ReplayRetentionSettingsEnvelopeSchema, res);
      return {
        data: (decoded.data ?? { enabled: false, days: 0 }),
        _meta: metaToShape(decoded.meta),
      };
    },
    onSuccess: async () => queryClient.invalidateQueries({ queryKey: ['/v1/panel/settings/replay-retention'] }),
  });
}

const DATA_RETENTION_PATH = '/v1/panel/settings/data-retention';

export interface DataRetentionSettings {
  chatLogDays: number;
  auditLogDays: number;
}

export function useDataRetentionSettings() {
  return useQuery<SettingsEnvelope<DataRetentionSettings> | null>({
    queryKey: [DATA_RETENTION_PATH],
    queryFn: async () => {
      const envelope = await fetchSettingsEnvelope(DataRetentionSettingsEnvelopeSchema, DATA_RETENTION_PATH);
      if (!envelope?.data) {
        return null;
      }
      return {
        data: { chatLogDays: envelope.data.chatLogDays, auditLogDays: envelope.data.auditLogDays },
        _meta: envelope._meta,
      };
    },
    staleTime: 1000 * 60 * 5,
    refetchOnWindowFocus: false
  });
}

export function useUpdateDataRetentionSettings() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({ expectedVersion, chatLogDays, auditLogDays }: DataRetentionSettings & { expectedVersion: number }): Promise<SettingsEnvelope<DataRetentionSettings>> => {
      const envelope = await protoSend(
        'PATCH',
        DATA_RETENTION_PATH,
        PatchDataRetentionSettingsRequestSchema,
        create(PatchDataRetentionSettingsRequestSchema, { expectedVersion: BigInt(expectedVersion), chatLogDays, auditLogDays }),
        DataRetentionSettingsEnvelopeSchema,
      );
      return {
        data: {
          chatLogDays: envelope.data?.chatLogDays ?? chatLogDays,
          auditLogDays: envelope.data?.auditLogDays ?? auditLogDays,
        },
        _meta: metaToShape(envelope.meta),
      };
    },
    onSuccess: async () => queryClient.invalidateQueries({ queryKey: [DATA_RETENTION_PATH] }),
  });
}

export function useLabels() {
  return useQuery({
    queryKey: ['/v1/panel/settings/ticket-labels', 'labels'],
    queryFn: async () => {
      const envelope = await fetchSettingsEnvelope(TicketLabelSettingsEnvelopeSchema, '/v1/panel/settings/ticket-labels');
      return envelope?.data?.labels ?? [];
    },
    staleTime: 1000 * 60 * 5,
    refetchOnWindowFocus: false
  });
}

export const GENERAL_SETTINGS_PATH = '/v1/panel/settings/general';
const GENERAL_SETTINGS_QUERY_KEY = [GENERAL_SETTINGS_PATH] as const;

export interface GeneralSettingsSnapshot {
  serverDisplayName: string;
  defaultLanguage: string;
  homepageIconUrl: string;
  panelIconUrl: string;
  publicUrl: string;
  publicUrlManagedByConfig: boolean;
  serverNameManaged: boolean;
  version: number;
}

export type GeneralSettingsChanges = Partial<Pick<
  GeneralSettingsSnapshot,
  'serverDisplayName' | 'defaultLanguage' | 'homepageIconUrl' | 'panelIconUrl' | 'publicUrl'
>>;

function toGeneralSettingsSnapshot(envelope: GeneralSettingsEnvelope, fallbackVersion: number): GeneralSettingsSnapshot {
  return {
    serverDisplayName: envelope.data?.serverDisplayName ?? '',
    defaultLanguage: envelope.data?.defaultLanguage ?? '',
    homepageIconUrl: envelope.data?.homepageIconUrl ?? '',
    panelIconUrl: envelope.data?.panelIconUrl ?? '',
    publicUrl: envelope.data?.publicUrl ?? '',
    publicUrlManagedByConfig: envelope.data?.publicUrlManagedByConfig ?? false,
    serverNameManaged: envelope.data?.serverNameManaged ?? false,
    version: envelope.meta ? Number(envelope.meta.version) : fallbackVersion,
  };
}

async function patchGeneralSettings(expectedVersion: number, changes: GeneralSettingsChanges): Promise<GeneralSettingsSnapshot> {
  const envelope = await protoSend(
    'PATCH',
    GENERAL_SETTINGS_PATH,
    PatchGeneralSettingsRequestSchema,
    create(PatchGeneralSettingsRequestSchema, { expectedVersion: BigInt(expectedVersion), ...changes }),
    GeneralSettingsEnvelopeSchema,
  );
  return toGeneralSettingsSnapshot(envelope, expectedVersion + 1);
}

export function useGeneralSettings({ enabled = true }: QueryGate = {}) {
  return useQuery({
    queryKey: GENERAL_SETTINGS_QUERY_KEY,
    enabled,
    queryFn: async () => toGeneralSettingsSnapshot(await protoFetch(GeneralSettingsEnvelopeSchema, GENERAL_SETTINGS_PATH), 0),
    refetchOnWindowFocus: false,
    refetchOnMount: 'always',
  });
}

export function usePatchGeneralSettings() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ expectedVersion, changes }: { expectedVersion: number; changes: GeneralSettingsChanges }) =>
      patchGeneralSettings(expectedVersion, changes),
    onSuccess: (snapshot) => {
      queryClient.setQueryData(GENERAL_SETTINGS_QUERY_KEY, snapshot);
      void queryClient.invalidateQueries({ queryKey: ['/v1/settings'] });
      void queryClient.invalidateQueries({ queryKey: PUBLIC_SETTINGS_QUERY_KEY });
    },
    onError: (error) => {
      if (isConflictError(error)) {
        void queryClient.invalidateQueries({ queryKey: GENERAL_SETTINGS_QUERY_KEY });
      }
    },
  });
}

export type ServerIconKind = 'homepage' | 'panel';

const uploadedIconSchema = z.object({ url: z.string().min(1) });

export function useUploadServerIcon() {
  return useMutation({
    mutationFn: async ({ kind, file }: { kind: ServerIconKind; file: File }): Promise<string> => {
      const formData = new FormData();
      formData.append('icon', file);
      const response = await apiFetch(`/v1/panel/settings/upload-icon?iconType=${kind}`, { method: 'POST', body: formData });
      const bodyText = await response.text().catch(() => '');
      if (!response.ok) {
        throw new ProtoHttpError(response.status, response.statusText, bodyText);
      }
      const url = uploadedIconSchema.safeParse(parseJsonOrNull(bodyText)).data?.url;
      if (url === undefined) {
        throw new ProtoHttpError(response.status, response.statusText, bodyText);
      }
      return url;
    },
  });
}

export type PanelUrlSaveOutcome = 'saved' | 'invalid' | 'conflict' | 'failed';

export function panelUrlSaveFailure(error: unknown): Exclude<PanelUrlSaveOutcome, 'saved'> {
  if (error instanceof ProtoHttpError && error.status === 400) return 'invalid';
  if (isConflictError(error)) return 'conflict';
  return 'failed';
}

export const API_KEY_PATH = '/v1/panel/settings/api-keys/panel';
const API_KEY_STATUS_QUERY_KEY = [API_KEY_PATH, 'exists'] as const;
const API_KEY_SECRET_QUERY_KEY = [API_KEY_PATH, 'secret'] as const;

export interface ApiKeyStatus {
  exists: boolean;
  managedByConfig: boolean;
}

export function useApiKeyStatus(enabled: boolean) {
  return useQuery<ApiKeyStatus>({
    queryKey: API_KEY_STATUS_QUERY_KEY,
    queryFn: async () => {
      const status = await protoFetch(ApiKeyExistsResponseSchema, `${API_KEY_PATH}/exists`);
      return { exists: status.exists, managedByConfig: status.managedByConfig };
    },
    enabled,
    staleTime: 60_000,
  });
}

async function fetchApiKeySecret(): Promise<string> {
  return (await protoFetch(ApiKeyRevealResponseSchema, `${API_KEY_PATH}/reveal`)).apiKey;
}

export function useApiKeySecret() {
  const queryClient = useQueryClient();
  const { data, isFetching } = useQuery({
    queryKey: API_KEY_SECRET_QUERY_KEY,
    queryFn: fetchApiKeySecret,
    enabled: (query) => query.state.data !== undefined,
    staleTime: Infinity,
    gcTime: 0,
    retry: false,
  });
  const fetchSecret = useCallback(
    async () => queryClient.query({ queryKey: API_KEY_SECRET_QUERY_KEY, queryFn: fetchApiKeySecret, staleTime: Infinity }),
    [queryClient],
  );
  return { secret: data ?? null, isFetching, fetchSecret };
}

export function useGenerateApiKey() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (): Promise<string> =>
      (await protoFetch(ApiKeyGenerateResponseSchema, `${API_KEY_PATH}/generate`, { method: 'POST' })).apiKey,
    onSuccess: async (apiKey) => {
      queryClient.setQueryData(API_KEY_SECRET_QUERY_KEY, apiKey);
      return queryClient.invalidateQueries({ queryKey: API_KEY_STATUS_QUERY_KEY });
    },
  });
}

export const MAIL_SETTINGS_PATH = '/v1/panel/settings/mail';
const MAIL_SETTINGS_QUERY_KEY = [MAIL_SETTINGS_PATH] as const;

export type MailSourceId = 'none' | 'config' | 'panel';

export interface MailServerSettings {
  host: string;
  port: number;
  username: string;
  from: string;
  starttls: boolean;
}

export interface MailSettingsState {
  source: MailSourceId;
  settings: MailServerSettings | null;
  passwordSet: boolean;
}

export interface MailCredentials {
  settings: MailServerSettings;
  password?: string;
}

export type MailFailureReason = 'connect-failed' | 'tls-failed' | 'auth-failed' | 'rejected' | 'timed-out';

export type MailTestOutcomeId = 'delivered' | MailFailureReason;

export interface MailTestResult {
  outcome: MailTestOutcomeId;
  detail: string;
  recipient: string;
}

export type MailSaveFailure =
  | { kind: 'refused'; reason: MailFailureReason; detail: string }
  | { kind: 'managed-by-config' }
  | { kind: 'failed' };

const MAIL_SOURCES: ReadonlyMap<MailSource, MailSourceId> = new Map<MailSource, MailSourceId>([
  [MailSource.UNSPECIFIED, 'none'],
  [MailSource.NONE, 'none'],
  [MailSource.CONFIG, 'config'],
  [MailSource.PANEL, 'panel'],
]);

const MAIL_TEST_OUTCOMES: ReadonlyMap<MailTestOutcome, MailTestOutcomeId> = new Map<MailTestOutcome, MailTestOutcomeId>([
  [MailTestOutcome.UNSPECIFIED, 'rejected'],
  [MailTestOutcome.DELIVERED, 'delivered'],
  [MailTestOutcome.CONNECT_FAILED, 'connect-failed'],
  [MailTestOutcome.TLS_FAILED, 'tls-failed'],
  [MailTestOutcome.AUTH_FAILED, 'auth-failed'],
  [MailTestOutcome.REJECTED, 'rejected'],
  [MailTestOutcome.TIMED_OUT, 'timed-out'],
]);

const MAIL_FAILURE_CODES: ReadonlyMap<string, MailFailureReason> = new Map<string, MailFailureReason>([
  ['MAIL_CONNECT_FAILED', 'connect-failed'],
  ['MAIL_TLS_FAILED', 'tls-failed'],
  ['MAIL_AUTH_FAILED', 'auth-failed'],
  ['MAIL_REJECTED', 'rejected'],
  ['MAIL_TIMED_OUT', 'timed-out'],
]);

function toMailSettingsState(response: MailSettingsResponse): MailSettingsState {
  const settings = response.settings;
  return {
    source: MAIL_SOURCES.get(response.source) ?? 'none',
    settings: settings
      ? { host: settings.host, port: settings.port, username: settings.username, from: settings.from, starttls: settings.starttls }
      : null,
    passwordSet: response.passwordSet,
  };
}

export function classifyMailSaveFailure(error: unknown): MailSaveFailure {
  const code = apiErrorCode(error);
  if (code === 'MAIL_MANAGED_BY_CONFIG') return { kind: 'managed-by-config' };
  const reason = MAIL_FAILURE_CODES.get(code ?? '');
  if (!reason || !(error instanceof ProtoHttpError)) return { kind: 'failed' };
  return { kind: 'refused', reason, detail: serverErrorMessage(error) ?? '' };
}

export function useMailSettings({ enabled = true }: QueryGate = {}) {
  return useQuery({
    queryKey: MAIL_SETTINGS_QUERY_KEY,
    enabled,
    queryFn: async () => toMailSettingsState(await protoFetch(MailSettingsResponseSchema, MAIL_SETTINGS_PATH)),
    staleTime: 60_000,
    refetchOnWindowFocus: false,
  });
}

function useMailSettingsMutation<Input>(send: (input: Input) => Promise<MailSettingsResponse>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: Input) => toMailSettingsState(await send(input)),
    onSuccess: (state) => {
      queryClient.setQueryData(MAIL_SETTINGS_QUERY_KEY, state);
      void queryClient.invalidateQueries({ queryKey: PUBLIC_SETTINGS_QUERY_KEY });
    },
    onError: (error) => {
      if (classifyMailSaveFailure(error).kind === 'managed-by-config') {
        void queryClient.invalidateQueries({ queryKey: MAIL_SETTINGS_QUERY_KEY });
      }
    },
  });
}

export function useSaveMailSettings() {
  return useMailSettingsMutation(async ({ settings, password }: MailCredentials) => protoSend(
    'PUT',
    MAIL_SETTINGS_PATH,
    UpdateMailSettingsRequestSchema,
    create(UpdateMailSettingsRequestSchema, { settings, password }),
    MailSettingsResponseSchema,
  ));
}

export function useRemoveMailSettings() {
  return useMailSettingsMutation(async () => protoFetch(MailSettingsResponseSchema, MAIL_SETTINGS_PATH, { method: 'DELETE' }));
}

export function useSendTestMail() {
  return useMutation({
    mutationFn: async (credentials: MailCredentials | null): Promise<MailTestResult> => {
      const result = await protoSend(
        'POST',
        `${MAIL_SETTINGS_PATH}/test`,
        TestMailRequestSchema,
        create(TestMailRequestSchema, credentials ?? {}),
        TestMailResponseSchema,
      );
      return { outcome: MAIL_TEST_OUTCOMES.get(result.outcome) ?? 'rejected', detail: result.detail, recipient: result.recipient };
    },
  });
}
