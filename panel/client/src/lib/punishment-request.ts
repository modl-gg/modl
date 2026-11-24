import { z } from 'zod';
import { hasStaffChosenDuration, isManualPunishment, playerFacingReason, requiresPlayerFacingReason } from './manual-punishments.ts';
import {
  DURATION_UNIT_MILLIS,
  OFFENSE_LEVELS,
  isPermanentUntilIdentityChange,
  type OffenseLevel,
  type PunishmentSeverity,
} from './punishment-preview.ts';

export type PunishmentFormIssue =
  | 'missingCategory'
  | 'missingReason'
  | 'missingSeverity'
  | 'invalidDuration'
  | 'unknownType';

export interface PunishmentFormIssueKeys {
  readonly title: string;
  readonly description: string;
}

export const PUNISHMENT_FORM_ISSUE_KEYS: Readonly<Record<PunishmentFormIssue, PunishmentFormIssueKeys>> = {
  missingCategory: { title: 'punishment.missingInfo', description: 'punishment.selectCategory' },
  missingReason: { title: 'punishment.missingInfo', description: 'punishment.provideReason' },
  missingSeverity: { title: 'punishment.missingInfo', description: 'punishment.selectSeverityLevel' },
  invalidDuration: { title: 'punishment.invalidDuration', description: 'punishment.invalidDurationDesc' },
  unknownType: { title: 'punishment.invalidType', description: 'punishment.unknownType' },
};

export class PunishmentFormError extends Error {
  readonly issue: PunishmentFormIssue;

  constructor(issue: PunishmentFormIssue) {
    super(issue);
    this.name = 'PunishmentFormError';
    this.issue = issue;
  }
}

export interface PunishmentFormDuration {
  readonly value: number;
  readonly unit: string;
}

export interface PunishmentRequestForm {
  readonly selectedPunishmentCategory?: string | undefined;
  readonly selectedSeverity?: PunishmentSeverity | undefined;
  readonly selectedOffenseLevel?: OffenseLevel | undefined;
  readonly duration?: PunishmentFormDuration | undefined;
  readonly isPermanent?: boolean | undefined;
  readonly reason?: string | undefined;
  readonly evidence?: readonly string[] | undefined;
  readonly staffNotes?: string | undefined;
  readonly altBlocking?: boolean | undefined;
  readonly statWiping?: boolean | undefined;
  readonly silentPunishment?: boolean | undefined;
  readonly kickSameIP?: boolean | undefined;
  readonly attachReports?: readonly string[] | undefined;
  readonly banToLink?: string | undefined;
  readonly banLinkedAccounts?: boolean | undefined;
}

export interface PunishmentRequestType {
  readonly name: string;
  readonly ordinal?: number | undefined;
  readonly singleSeverityPunishment?: boolean | undefined;
  readonly permanentUntilSkinChange?: boolean | undefined;
  readonly permanentUntilUsernameChange?: boolean | undefined;
}

export interface PunishmentRequestContext {
  readonly punishmentTypes: readonly PunishmentRequestType[];
  readonly ticketIds?: readonly string[] | undefined;
}

export interface PunishmentNoteInit {
  readonly text: string;
}

export type PunishmentEvidenceType = 'file' | 'url' | 'text';

export interface PunishmentEvidenceInit {
  readonly text: string;
  readonly type: PunishmentEvidenceType;
  readonly fileUrl?: string | undefined;
  readonly fileName?: string | undefined;
  readonly fileType?: string | undefined;
  readonly fileSize?: number | undefined;
}

export interface PunishmentApplicationData {
  readonly silent: boolean;
  readonly altBlocking?: true | undefined;
  readonly wipeAfterExpiry?: true | undefined;
  readonly banLinkedAccounts?: true | undefined;
  readonly kickSameIP?: true | undefined;
  readonly linkedBanId?: string | undefined;
}

export interface PunishmentApplicationRequest {
  readonly typeOrdinal: number;
  readonly reason?: string | undefined;
  readonly severity?: string | undefined;
  readonly status?: string | undefined;
  readonly duration?: number | undefined;
  readonly notes: readonly PunishmentNoteInit[];
  readonly evidence: readonly PunishmentEvidenceInit[];
  readonly attachedTicketIds: readonly string[];
  readonly data: PunishmentApplicationData;
}

export type PunishmentRequestResult =
  | { readonly ok: true; readonly request: PunishmentApplicationRequest }
  | { readonly ok: false; readonly issue: PunishmentFormIssue };

const SEVERITY_REQUEST_VALUES = {
  Lenient: 'lenient',
  Regular: 'regular',
  Aggravated: 'severe',
} as const satisfies Record<PunishmentSeverity, string>;

const SINGLE_SEVERITY = 'single';
const NEW_TICKET_PLACEHOLDER = 'ticket-new';
const DEFAULT_FILE_TYPE = 'application/octet-stream';
const WEB_PROTOCOLS: ReadonlySet<string> = new Set(['http:', 'https:']);

const uploadedEvidenceSchema = z.object({
  url: z.string().min(1),
  fileName: z.string().min(1),
  fileType: z.string().min(1).catch(DEFAULT_FILE_TYPE),
  fileSize: z.number().int().nonnegative().catch(0),
});

export function durationToMilliseconds(duration: PunishmentFormDuration): number | null {
  const multiplier = DURATION_UNIT_MILLIS.get(duration.unit);
  return multiplier === undefined ? null : duration.value * multiplier;
}

export function isWebUrl(text: string): boolean {
  try {
    return WEB_PROTOCOLS.has(new URL(text).protocol);
  } catch {
    return false;
  }
}

function isBlank(value: string | undefined): boolean {
  return (value?.trim() ?? '') === '';
}

function hasValidManualDuration(duration: PunishmentFormDuration | undefined): boolean {
  return duration !== undefined && duration.value > 0 && DURATION_UNIT_MILLIS.has(duration.unit);
}

export function punishmentFormIssue(form: PunishmentRequestForm, punishmentType: PunishmentRequestType): PunishmentFormIssue | null {
  const category = punishmentType.name;
  const singleSeverity = punishmentType.singleSeverityPunishment === true;
  if (requiresPlayerFacingReason(category) && isBlank(form.reason)) return 'missingReason';
  if (isPermanentUntilIdentityChange(punishmentType)) return null;
  if (!singleSeverity && form.selectedSeverity === undefined && !isManualPunishment(category)) return 'missingSeverity';
  if (hasStaffChosenDuration(category) && form.isPermanent !== true && !hasValidManualDuration(form.duration)) return 'invalidDuration';
  return null;
}

function severityAndStatus(
  form: PunishmentRequestForm,
  punishmentType: PunishmentRequestType,
): { severity?: string | undefined; status?: string | undefined } {
  if (isManualPunishment(punishmentType.name) || isPermanentUntilIdentityChange(punishmentType)) return {};
  if (punishmentType.singleSeverityPunishment === true) {
    return {
      severity: SINGLE_SEVERITY,
      status: OFFENSE_LEVELS.find((level) => level.id === form.selectedOffenseLevel)?.status,
    };
  }
  return form.selectedSeverity === undefined ? {} : { severity: SEVERITY_REQUEST_VALUES[form.selectedSeverity] };
}

function manualDuration(form: PunishmentRequestForm, category: string): number | undefined {
  if (!hasStaffChosenDuration(category) || form.isPermanent === true || form.duration === undefined) return undefined;
  return durationToMilliseconds(form.duration) ?? undefined;
}

function nonBlank(value: string | undefined): string | undefined {
  const trimmed = value?.trim() ?? '';
  return trimmed === '' ? undefined : trimmed;
}

function applicationData(form: PunishmentRequestForm): PunishmentApplicationData {
  return {
    silent: form.silentPunishment === true,
    altBlocking: form.altBlocking === true ? true : undefined,
    wipeAfterExpiry: form.statWiping === true ? true : undefined,
    banLinkedAccounts: form.banLinkedAccounts === true ? true : undefined,
    kickSameIP: form.kickSameIP === true ? true : undefined,
    linkedBanId: nonBlank(form.banToLink),
  };
}

function attachedTicketId(report: string): string | null {
  const trimmed = report.trim();
  if (trimmed === '' || trimmed === NEW_TICKET_PLACEHOLDER) return null;
  return /ticket-(\w+)/.exec(trimmed)?.[1] ?? trimmed;
}

function attachedTicketIds(contextTicketIds: readonly string[], reports: readonly string[]): string[] {
  const ids = [...contextTicketIds, ...reports.flatMap((report) => attachedTicketId(report) ?? [])];
  return [...new Set(ids.filter((id) => id !== ''))];
}

function parseUploadedEvidence(text: string): z.infer<typeof uploadedEvidenceSchema> | null {
  try {
    const parsed: unknown = JSON.parse(text);
    return uploadedEvidenceSchema.safeParse(parsed).data ?? null;
  } catch {
    return null;
  }
}

function uploadedEvidence(text: string): PunishmentEvidenceInit | null {
  if (!text.startsWith('{')) return null;
  const uploaded = parseUploadedEvidence(text);
  return uploaded === null ? null : {
    text: uploaded.fileName,
    type: 'file',
    fileUrl: uploaded.url,
    fileName: uploaded.fileName,
    fileType: uploaded.fileType,
    fileSize: uploaded.fileSize,
  };
}

export function textEvidenceType(text: string): 'url' | 'text' {
  return isWebUrl(text) ? 'url' : 'text';
}

function punishmentEvidence(entries: readonly string[]): PunishmentEvidenceInit[] {
  return entries
    .map((entry) => entry.trim())
    .filter((entry) => entry !== '')
    .map((text) => uploadedEvidence(text) ?? { text, type: textEvidenceType(text) });
}

function staffNotes(staffNotesText: string | undefined): PunishmentNoteInit[] {
  const text = staffNotesText?.trim() ?? '';
  return text === '' ? [] : [{ text }];
}

export function buildPunishmentRequest(form: PunishmentRequestForm, context: PunishmentRequestContext): PunishmentRequestResult {
  const category = form.selectedPunishmentCategory ?? '';
  if (category === '') return { ok: false, issue: 'missingCategory' };

  const punishmentType = context.punishmentTypes.find((type) => type.name === category);
  if (punishmentType?.ordinal === undefined) return { ok: false, issue: 'unknownType' };
  const issue = punishmentFormIssue(form, punishmentType);
  if (issue !== null) return { ok: false, issue };

  return {
    ok: true,
    request: {
      typeOrdinal: punishmentType.ordinal,
      reason: playerFacingReason(category, form.reason),
      ...severityAndStatus(form, punishmentType),
      notes: staffNotes(form.staffNotes),
      evidence: punishmentEvidence(form.evidence ?? []),
      attachedTicketIds: attachedTicketIds(context.ticketIds ?? [], form.attachReports ?? []),
      duration: manualDuration(form, category),
      data: applicationData(form),
    },
  };
}
