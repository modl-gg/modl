import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { create, type MessageInitShape } from '@bufbuild/protobuf';
import {
  PunishmentTypeRequestSchema,
  PunishmentTypeSchema,
  type AppealForm,
  type DurationDetail,
  type OffenseLevelDurations,
  type PunishmentDurations,
  type PunishmentPoints,
  type PunishmentType,
} from '@proto/modl/v1/settings_pb.ts';
import { apiFetch } from '@/lib/api';
import { optionalField } from '@/lib/defined-fields';
import { protoHttpErrorOf, protoSend } from '@/lib/proto-fetch';
import type { AppealFormField, AppealFormSection, AppealFormSettings } from '@/types/forms';
import { punishmentTypesQuery } from './punishments';
import type { QueryGate } from './query-gate';

const PUNISHMENT_TYPES_PATH = '/v1/panel/settings/punishment-types';

export const DURATION_UNITS = ['seconds', 'minutes', 'hours', 'days', 'weeks', 'months'] as const;
export type DurationUnit = (typeof DURATION_UNITS)[number];

export const DURATION_KINDS = ['mute', 'ban', 'permanent mute', 'permanent ban'] as const;
export type DurationKind = (typeof DURATION_KINDS)[number];

export const OFFENSE_LEVELS = ['first', 'medium', 'habitual'] as const;
export type OffenseLevel = (typeof OFFENSE_LEVELS)[number];

export const SEVERITIES = ['low', 'regular', 'severe'] as const;
export type Severity = (typeof SEVERITIES)[number];

export const PUNISHMENT_CATEGORIES = ['Gameplay', 'Social', 'Administrative'] as const;
export type PunishmentCategory = (typeof PUNISHMENT_CATEGORIES)[number];
export type CustomPunishmentCategory = Exclude<PunishmentCategory, 'Administrative'>;

export const APPEAL_FORM_FIELD_TYPES = [
  'text', 'textarea', 'dropdown', 'multiple_choice', 'checkbox', 'file_upload', 'checkboxes',
] as const satisfies readonly AppealFormField['type'][];

export interface DurationSetting {
  value: number;
  unit: DurationUnit;
  type: DurationKind;
}

export type OffenseDurations = Record<OffenseLevel, DurationSetting>;
export type SeverityDurations = Record<Severity, OffenseDurations>;
export type SeverityPoints = Record<Severity, number>;

export interface PunishmentTypeConfig {
  id: number;
  ordinal: number;
  name: string;
  category: PunishmentCategory;
  isCustomizable: boolean;
  durations?: SeverityDurations;
  points?: SeverityPoints;
  customPoints?: number;
  appealForm?: AppealFormSettings;
  staffDescription: string;
  playerDescription: string;
  canBeAltBlocking: boolean;
  canBeStatWiping: boolean;
  isAppealable: boolean;
  singleSeverityPunishment: boolean;
  singleSeverityDurations?: OffenseDurations;
  singleSeverityPoints?: number;
  permanentUntilUsernameChange: boolean;
  permanentUntilSkinChange: boolean;
}

export interface NewPunishmentType {
  name: string;
  category: CustomPunishmentCategory;
  durations: SeverityDurations;
  points: SeverityPoints;
}

function pick<const Options extends readonly string[]>(options: Options, value: string | undefined): Options[number] | undefined {
  return options.find((option) => option === value);
}

function optionalText(value: string): string | undefined {
  return value === '' ? undefined : value;
}

function toDurationSetting(detail: DurationDetail | undefined): DurationSetting {
  return {
    value: detail?.value ?? 0,
    unit: pick(DURATION_UNITS, detail?.unit) ?? 'hours',
    type: pick(DURATION_KINDS, detail?.type) ?? 'mute',
  };
}

function toOffenseDurations(durations: OffenseLevelDurations | undefined): OffenseDurations {
  return {
    first: toDurationSetting(durations?.first),
    medium: toDurationSetting(durations?.medium),
    habitual: toDurationSetting(durations?.habitual),
  };
}

function toOptionalOffenseDurations(durations: OffenseLevelDurations | undefined): OffenseDurations | undefined {
  return durations === undefined ? undefined : toOffenseDurations(durations);
}

function toSeverityDurations(durations: PunishmentDurations): SeverityDurations {
  return {
    low: toOffenseDurations(durations.low),
    regular: toOffenseDurations(durations.regular),
    severe: toOffenseDurations(durations.severe),
  };
}

function toSeverityPoints(points: PunishmentPoints): SeverityPoints {
  return { low: points.low, regular: points.regular, severe: points.severe };
}

function toAppealForm(form: AppealForm): AppealFormSettings {
  return {
    fields: form.fields.map((field, index): AppealFormField => ({
      id: field.id,
      type: pick(APPEAL_FORM_FIELD_TYPES, field.type) ?? 'text',
      label: field.label,
      ...optionalField('description', optionalText(field.description)),
      required: field.required ?? false,
      order: field.order ?? index,
      options: [...field.options],
      optionSectionMapping: { ...field.optionSectionMapping },
      ...optionalField('sectionId', optionalText(field.sectionId)),
    })),
    sections: form.sections.map((section, index): AppealFormSection => ({
      id: section.id,
      title: section.title,
      ...optionalField('description', optionalText(section.description)),
      order: section.order ?? index,
      hideByDefault: section.hideByDefault,
    })),
  };
}

function toPunishmentCategory(category: string): PunishmentCategory {
  return pick(PUNISHMENT_CATEGORIES, category) ?? 'Administrative';
}

function toPunishmentTypeConfig(type: PunishmentType, ordinal: number): PunishmentTypeConfig {
  return {
    id: type.id ?? ordinal,
    ordinal,
    name: type.name,
    category: toPunishmentCategory(type.category),
    isCustomizable: type.customizable ?? false,
    ...optionalField('durations', type.durations === undefined ? undefined : toSeverityDurations(type.durations)),
    ...optionalField('points', type.points === undefined ? undefined : toSeverityPoints(type.points)),
    ...optionalField('customPoints', type.customPoints),
    ...optionalField('appealForm', type.appealForm === undefined ? undefined : toAppealForm(type.appealForm)),
    staffDescription: type.staffDescription,
    playerDescription: type.playerDescription,
    canBeAltBlocking: type.canBeAltBlocking ?? false,
    canBeStatWiping: type.canBeStatWiping ?? false,
    isAppealable: type.appealable ?? true,
    singleSeverityPunishment: type.singleSeverityPunishment ?? false,
    ...optionalField('singleSeverityDurations', toOptionalOffenseDurations(type.singleSeverityDurations)),
    ...optionalField('singleSeverityPoints', type.singleSeverityPoints),
    permanentUntilUsernameChange: type.permanentUntilUsernameChange ?? false,
    permanentUntilSkinChange: type.permanentUntilSkinChange ?? false,
  };
}

function toPunishmentTypeConfigs(types: PunishmentType[]): PunishmentTypeConfig[] {
  return types.flatMap((type) => (type.ordinal === undefined ? [] : [toPunishmentTypeConfig(type, type.ordinal)]));
}

type PunishmentTypeRequestInit = MessageInitShape<typeof PunishmentTypeRequestSchema>;

function toAppealFormInit(form: AppealFormSettings): PunishmentTypeRequestInit['appealForm'] {
  return {
    fields: form.fields.map((field) => ({
      id: field.id,
      type: field.type,
      label: field.label,
      ...optionalField('description', field.description),
      required: field.required,
      order: field.order,
      ...optionalField('sectionId', field.sectionId),
      ...optionalField('options', field.options),
      ...optionalField('optionSectionMapping', field.optionSectionMapping),
    })),
    sections: form.sections.map((section) => ({
      id: section.id,
      title: section.title,
      ...optionalField('description', section.description),
      order: section.order,
      ...optionalField('hideByDefault', section.hideByDefault),
    })),
  };
}

function toPunishmentTypeRequest(config: PunishmentTypeConfig): PunishmentTypeRequestInit {
  return {
    name: config.name,
    category: config.category,
    durations: config.durations,
    singleSeverityDurations: config.singleSeverityDurations,
    singleSeverityPoints: config.singleSeverityPoints,
    points: config.points,
    customPoints: config.customPoints,
    staffDescription: config.staffDescription,
    playerDescription: config.playerDescription,
    singleSeverityPunishment: config.singleSeverityPunishment,
    canBeAltBlocking: config.canBeAltBlocking,
    canBeStatWiping: config.canBeStatWiping,
    appealable: config.isAppealable,
    appealForm: config.appealForm === undefined ? undefined : toAppealFormInit(config.appealForm),
    permanentUntilSkinChange: config.permanentUntilSkinChange,
    permanentUntilUsernameChange: config.permanentUntilUsernameChange,
  };
}

export function usePunishmentTypeConfigs({ enabled = true }: QueryGate = {}) {
  return useQuery({ ...punishmentTypesQuery(), enabled, refetchOnWindowFocus: false, select: toPunishmentTypeConfigs });
}

function usePunishmentTypesCache() {
  const queryClient = useQueryClient();
  const { queryKey } = punishmentTypesQuery();
  return {
    update: (change: (types: PunishmentType[]) => PunishmentType[]) => {
      queryClient.setQueryData(queryKey, (types) => (types === undefined ? types : change(types)));
    },
  };
}

export function useCreatePunishmentType() {
  const cache = usePunishmentTypesCache();
  return useMutation({
    mutationFn: async (type: NewPunishmentType) => protoSend(
      'POST',
      PUNISHMENT_TYPES_PATH,
      PunishmentTypeRequestSchema,
      create(PunishmentTypeRequestSchema, type),
      PunishmentTypeSchema,
    ),
    onSuccess: (created) => {
      cache.update((types) => [...types, created]);
    },
  });
}

export function useUpdatePunishmentType() {
  const cache = usePunishmentTypesCache();
  return useMutation({
    mutationFn: async (config: PunishmentTypeConfig) => protoSend(
      'PATCH',
      `${PUNISHMENT_TYPES_PATH}/${config.ordinal}`,
      PunishmentTypeRequestSchema,
      create(PunishmentTypeRequestSchema, toPunishmentTypeRequest(config)),
      PunishmentTypeSchema,
    ),
    onSuccess: (saved, config) => {
      cache.update((types) => types.map((type) => (type.ordinal === config.ordinal ? saved : type)));
    },
  });
}

export function useDeletePunishmentType() {
  const cache = usePunishmentTypesCache();
  return useMutation({
    mutationFn: async (ordinal: number): Promise<void> => {
      const response = await apiFetch(`${PUNISHMENT_TYPES_PATH}/${ordinal}`, { method: 'DELETE' });
      if (!response.ok) {
        throw await protoHttpErrorOf(response);
      }
    },
    onSuccess: (_result, ordinal) => {
      cache.update((types) => types.filter((type) => type.ordinal !== ordinal));
    },
  });
}
