import { z } from 'zod';
import { apiFetch, type ApiRequestOptions } from '@/lib/api';

export type PublicRecordKind = 'tickets' | 'appeals';

const storedGrantSchema = z.object({
  token: z.string(),
  grant: z.enum(['access-key', 'verification']),
  expiresAt: z.number(),
});

type StoredGrant = z.infer<typeof storedGrantSchema>;
export type PublicRecordGrant = StoredGrant['grant'];

const STORAGE_PREFIX = 'modl.recordAccess.';
const GRANT_LIFETIME_MS = 7 * 24 * 60 * 60 * 1000;
const memoryGrants = new Map<string, StoredGrant>();

function grantStore(): Storage | null {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

function storageKey(recordId: string): string {
  return `${STORAGE_PREFIX}${recordId}`;
}

function readStoredGrant(recordId: string): StoredGrant | null {
  try {
    const parsed: unknown = JSON.parse(grantStore()?.getItem(storageKey(recordId)) ?? 'null');
    return storedGrantSchema.safeParse(parsed).data ?? null;
  } catch {
    return null;
  }
}

function forgetGrant(recordId: string): void {
  memoryGrants.delete(recordId);
  try {
    grantStore()?.removeItem(storageKey(recordId));
  } catch {
    return;
  }
}

function readGrant(recordId: string): StoredGrant | null {
  const grant = readStoredGrant(recordId) ?? memoryGrants.get(recordId) ?? null;
  if (grant && grant.expiresAt > Date.now()) return grant;
  if (grant) forgetGrant(recordId);
  return null;
}

export function rememberPublicRecordGrant(recordId: string, token: string, grant: PublicRecordGrant): void {
  const stored: StoredGrant = { token, grant, expiresAt: Date.now() + GRANT_LIFETIME_MS };
  memoryGrants.set(recordId, stored);
  try {
    grantStore()?.setItem(storageKey(recordId), JSON.stringify(stored));
  } catch {
    return;
  }
}

export function readPublicRecordToken(recordId: string): string | null {
  return readGrant(recordId)?.token ?? null;
}

export function readPublicRecordAccessKey(recordId: string): string | null {
  const grant = readGrant(recordId);
  return grant?.grant === 'access-key' ? grant.token : null;
}

export async function publicRecordFetch(
  kind: PublicRecordKind,
  recordId: string,
  subPath = '',
  options?: ApiRequestOptions,
): Promise<Response> {
  const path = `/v1/public/${kind}/${encodeURIComponent(recordId)}${subPath}`;
  const token = readPublicRecordToken(recordId);
  return apiFetch(token ? `${path}?token=${encodeURIComponent(token)}` : path, options);
}
