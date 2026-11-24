import { create, fromJsonString, toJson, type DescMessage, type JsonValue, type MessageShape } from '@bufbuild/protobuf';
import {
  PublicTicketVerificationRequestResponseSchema,
  PublicTicketVerificationResponseSchema,
  TicketVerificationRequiredResponseSchema,
  VerifyTicketCodeRequestSchema,
  type TicketVerificationRequiredResponse,
} from '@proto/modl/v1/ticket_pb.ts';
import { decodeProtoResponse, ProtoHttpError, protoHttpErrorOf, READ_OPTS } from '@/lib/proto-fetch';
import {
  publicRecordFetch,
  rememberPublicRecordGrant,
  type PublicRecordKind,
} from '@/lib/public-record-access';

export interface PublicRecordVerificationRequired {
  requiresVerification: true;
  emailHint: string;
  recordId: string;
}

function readVerificationRequirement(bodyText: string): TicketVerificationRequiredResponse | null {
  try {
    return fromJsonString(TicketVerificationRequiredResponseSchema, bodyText, READ_OPTS);
  } catch {
    return null;
  }
}

async function verificationRequirementOf(
  res: Response,
  recordId: string,
): Promise<PublicRecordVerificationRequired | null> {
  const bodyText = await res.text().catch(() => '');
  const requirement = readVerificationRequirement(bodyText);
  if (requirement !== null && !requirement.requiresVerification) {
    throw new ProtoHttpError(res.status, res.statusText, bodyText);
  }
  return { requiresVerification: true, emailHint: requirement?.emailHint ?? '', recordId };
}

export async function fetchPublicRecord<Desc extends DescMessage>(
  kind: PublicRecordKind,
  recordId: string,
  schema: Desc,
): Promise<MessageShape<Desc> | PublicRecordVerificationRequired | null> {
  const res = await publicRecordFetch(kind, recordId);
  if (res.status === 404) return null;
  if (res.status === 403) return verificationRequirementOf(res, recordId);
  if (!res.ok) {
    throw await protoHttpErrorOf(res);
  }
  return decodeProtoResponse(schema, res);
}

export async function postPublicRecord<Desc extends DescMessage>(
  kind: PublicRecordKind,
  recordId: string,
  subPath: string,
  body: JsonValue,
  schema: Desc,
): Promise<MessageShape<Desc>> {
  const res = await publicRecordFetch(kind, recordId, subPath, { method: 'POST', body });
  if (!res.ok) {
    throw await protoHttpErrorOf(res);
  }
  return decodeProtoResponse(schema, res);
}

export async function requestPublicVerification(kind: PublicRecordKind, recordId: string) {
  const res = await publicRecordFetch(kind, recordId, '/request-verification', { method: 'POST' });
  if (!res.ok) {
    throw await protoHttpErrorOf(res);
  }
  return decodeProtoResponse(PublicTicketVerificationRequestResponseSchema, res);
}

export async function verifyPublicCode(kind: PublicRecordKind, recordId: string, code: string) {
  const res = await publicRecordFetch(kind, recordId, '/verify', {
    method: 'POST',
    body: toJson(VerifyTicketCodeRequestSchema, create(VerifyTicketCodeRequestSchema, { code })),
  });
  if (!res.ok) {
    throw await protoHttpErrorOf(res);
  }
  const verification = await decodeProtoResponse(PublicTicketVerificationResponseSchema, res);
  if (verification.token) {
    rememberPublicRecordGrant(recordId, verification.token, 'verification');
  }
  return verification;
}
