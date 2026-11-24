import { toNum } from '@/lib/proto-ui';
import { withoutReasonCopy } from '@/lib/punishment-notes';
import type {
  PunishmentResponse,
  PunishmentModification,
  PunishmentNote,
  PunishmentEvidence,
} from '@proto/modl/v1/punishment_pb.ts';

const epochToIso = (millis: bigint | undefined): string | null =>
  millis === undefined ? null : new Date(toNum(millis)).toISOString();

const mapModification = (mod: PunishmentModification) => ({
  ...mod,
  date: epochToIso(mod.date),
  effectiveDuration: mod.effectiveDuration === undefined ? undefined : toNum(mod.effectiveDuration),
});

const mapNote = (note: PunishmentNote) => ({
  ...note,
  date: epochToIso(note.date),
});

const mapEvidence = (evidence: PunishmentEvidence) => ({
  ...evidence,
  uploadedAt: epochToIso(evidence.uploadedAt),
  fileSize: evidence.fileSize === undefined ? undefined : toNum(evidence.fileSize),
});

export const mapPunishment = (punishment: PunishmentResponse) => ({
  ...punishment,
  issued: epochToIso(punishment.issued),
  expires: epochToIso(punishment.expires),
  started: epochToIso(punishment.started),
  duration: punishment.duration === undefined ? null : toNum(punishment.duration),
  modifications: punishment.modifications.map(mapModification),
  notes: withoutReasonCopy(punishment.notes, punishment.reason).map(mapNote),
  evidence: punishment.evidence.map(mapEvidence),
});
