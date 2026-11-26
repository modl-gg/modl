import type { PlayerDetail } from '@/hooks/data/players';
import type { PlayerTicket } from '@/hooks/data/tickets';
import type { EvidenceEntry } from '@/components/ui/evidence-list';
import type { PunishmentTypeConfig } from '@/lib/punishment-preview';
import { MANUAL_PUNISHMENTS } from '@/lib/manual-punishments';
import type { LinkedAccountResponse } from '@proto/modl/v1/player_pb.ts';
import { getEffectivePunishmentState, type EffectivePunishmentState } from '@/utils/punishment-status';

export type PlayerPunishmentRecord = PlayerDetail['punishments'][number];
export type PunishmentModificationRecord = PlayerPunishmentRecord['modifications'][number];
export type PunishmentEvidenceRecord = PlayerPunishmentRecord['evidence'][number];
export type PlayerNoteRecord = PlayerDetail['notes'][number];
export type PlayerUsernameRecord = PlayerDetail['usernames'][number];
export type PlayerAddressRecord = PlayerDetail['ipAddresses'][number];

export type OffenderLevel = 'low' | 'medium' | 'habitual';
export type PlayerRestriction = 'banned' | 'muted';

export interface PunishmentHistoryEntry {
  readonly punishment: PlayerPunishmentRecord;
  readonly typeName: string;
  readonly punishmentType: PunishmentTypeConfig | undefined;
  readonly effectiveState: EffectivePunishmentState<PunishmentModificationRecord>;
}

export interface LatestIpActivity {
  readonly lastLogin: string | null;
  readonly loginCount: number;
}

export interface PlayerProfile {
  readonly username: string;
  readonly isOnline: boolean;
  readonly restriction: PlayerRestriction | null;
  readonly region: string;
  readonly country: string;
  readonly latestIpActivity: LatestIpActivity | null;
  readonly firstJoined: string | null;
  readonly lastSeen: string | null;
  readonly lastServer: string;
  readonly playtimeSeconds: number;
  readonly social: OffenderLevel;
  readonly gameplay: OffenderLevel;
  readonly previousNames: readonly PlayerUsernameRecord[];
  readonly addresses: readonly PlayerAddressRecord[];
  readonly history: readonly PunishmentHistoryEntry[];
  readonly notes: readonly PlayerNoteRecord[];
  readonly linkedAccounts: readonly LinkedAccountResponse[];
}

export interface AttachableTicket {
  readonly id: string;
  readonly subject: string;
  readonly type: string;
  readonly status: string;
  readonly locked: boolean;
}

const KICK_ORDINAL = MANUAL_PUNISHMENTS.Kick.ordinal;
const LINKED_BAN_ORDINAL = MANUAL_PUNISHMENTS['Linked Ban'].ordinal;
const LIFECYCLE_STATUSES: ReadonlySet<string> = new Set(['active', 'inactive', 'unstarted', 'pardoned']);
const PLACEHOLDER_VALUES: ReadonlySet<string> = new Set(['', '0', 'null', 'undefined', 'false']);

function offenderLevel(value: string): OffenderLevel {
  const normalized = value.trim().toLowerCase();
  if (normalized === 'habitual') return 'habitual';
  if (normalized === 'medium') return 'medium';
  return 'low';
}

function restrictionOf(punishments: readonly PlayerPunishmentRecord[]): PlayerRestriction | null {
  const enforced = punishments.filter((punishment) => punishment.active && punishment.typeOrdinal !== KICK_ORDINAL);
  if (enforced.some((punishment) => punishment.effectiveCategory === 'BAN')) return 'banned';
  if (enforced.some((punishment) => punishment.effectiveCategory === 'MUTE')) return 'muted';
  return null;
}

function latestIpActivityOf(player: PlayerDetail): LatestIpActivity | null {
  const latestIp = player.latestIPData;
  if (latestIp === undefined) return null;
  return { lastLogin: latestIp.lastLogin || latestIp.firstLogin || null, loginCount: latestIp.loginCount };
}

function issuedTime(punishment: PlayerPunishmentRecord): number {
  return punishment.issued === null ? 0 : new Date(punishment.issued).getTime();
}

function historyEntry(punishment: PlayerPunishmentRecord, punishmentTypes: readonly PunishmentTypeConfig[]): PunishmentHistoryEntry {
  const punishmentType = punishmentTypes.find((type) => type.ordinal === punishment.typeOrdinal);
  return {
    punishment,
    typeName: punishmentType?.name ?? punishment.type,
    punishmentType,
    effectiveState: getEffectivePunishmentState(punishment),
  };
}

export function buildPlayerProfile(
  player: PlayerDetail,
  linkedAccounts: readonly LinkedAccountResponse[],
  punishmentTypes: readonly PunishmentTypeConfig[],
): PlayerProfile {
  const history = player.punishments
    .map((punishment) => historyEntry(punishment, punishmentTypes))
    .sort((a, b) => issuedTime(b.punishment) - issuedTime(a.punishment));
  const latestIpActivity = latestIpActivityOf(player);
  return {
    username: player.usernames.at(-1)?.username ?? '',
    isOnline: player.presence.isOnline,
    restriction: restrictionOf(player.punishments),
    region: player.latestIPData?.region ?? '',
    country: player.latestIPData?.country ?? '',
    firstJoined: player.presence.firstJoined,
    latestIpActivity,
    lastSeen: player.presence.lastSeen ?? latestIpActivity?.lastLogin ?? null,
    lastServer: player.lastServer ?? '',
    playtimeSeconds: Math.round(player.playtimeHours * 3600),
    social: offenderLevel(player.social),
    gameplay: offenderLevel(player.gameplay),
    previousNames: player.usernames.slice(0, -1),
    addresses: player.ipAddresses.toReversed(),
    history,
    notes: player.notes,
    linkedAccounts,
  };
}

function meaningfulValue(value: string | undefined): string | null {
  const trimmed = value?.trim() ?? '';
  return PLACEHOLDER_VALUES.has(trimmed) ? null : trimmed;
}

export function displaySeverity(punishment: PlayerPunishmentRecord): string | null {
  if (punishment.typeOrdinal === LINKED_BAN_ORDINAL) return null;
  const severity = meaningfulValue(punishment.severity);
  return severity?.toLowerCase() === 'low' ? 'lenient' : severity;
}

export function displayOffenseStatus(punishment: PlayerPunishmentRecord): string | null {
  if (punishment.typeOrdinal === LINKED_BAN_ORDINAL) return null;
  const status = meaningfulValue(punishment.status);
  return status === null || LIFECYCLE_STATUSES.has(status.toLowerCase()) ? null : status;
}

export function isRecordedPunishment(entry: PunishmentHistoryEntry): boolean {
  return entry.punishment.id !== '';
}

export function hasPunishmentDetails(entry: PunishmentHistoryEntry): boolean {
  const { punishment, effectiveState } = entry;
  return punishment.evidence.length > 0
    || punishment.notes.length > 0
    || punishment.attachedTicketIds.length > 0
    || (punishment.altBlocking === true && isRecordedPunishment(entry))
    || effectiveState.hasModifications;
}

export function punishmentReason(punishment: PlayerPunishmentRecord): string | null {
  const reason = punishment.reason?.trim() ?? '';
  return reason === '' ? null : reason;
}

export function toEvidenceEntry(evidence: PunishmentEvidenceRecord): EvidenceEntry {
  return {
    type: evidence.type,
    text: evidence.text,
    url: evidence.url,
    uploadedBy: evidence.uploadedBy,
    uploadedById: evidence.uploadedById,
    uploadedAt: evidence.uploadedAt ?? undefined,
    fileName: evidence.fileName,
    fileType: evidence.fileType,
    fileSize: evidence.fileSize,
  };
}

export function attachableTickets(tickets: readonly PlayerTicket[]): AttachableTicket[] {
  return tickets.flatMap((ticket) => ticket.id === undefined ? [] : [{
    id: ticket.id,
    subject: ticket.subject ?? '',
    type: ticket.type ?? '',
    status: ticket.locked ? 'Closed' : 'Open',
    locked: ticket.locked,
  }]);
}
