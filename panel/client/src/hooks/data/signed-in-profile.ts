import { queryOptions, useQuery, type QueryClient, type QueryKey } from '@tanstack/react-query';
import { PanelProfileResponseSchema, type PanelProfileResponse } from '@proto/modl/v1/auth_pb.ts';
import { optionalField } from '@/lib/defined-fields';
import { protoFetch } from '@/lib/proto-fetch';
import { ApiHttpError } from '@/lib/http-error';
import { RATE_LIMIT_AWARE_RETRY } from '@/lib/query-retry';
import { isPublicQueryKey } from '@/lib/public-queries';
import { DEFAULT_DATE_FORMAT } from '@/utils/date-utils';
import { toSecondFactorState, type SecondFactorStateId } from './sign-in';

const SIGNED_IN_PROFILE_PATH = '/v1/panel/auth/me';
const MINECRAFT_LINK_POLL_INTERVAL_MS = 3_000;
const HTTP_UNAUTHORIZED = 401;
const HTTP_STAFF_NOT_FOUND = 404;

export const SIGNED_IN_PROFILE_QUERY_KEY = [SIGNED_IN_PROFILE_PATH] as const;

export interface LinkedMinecraftAccount {
  uuid: string;
  username: string;
}

export interface SignedInUser {
  id: string;
  email: string;
  username: string;
  role: string;
  minecraftAccount: LinkedMinecraftAccount | null;
  language?: string;
  dateFormat: string;
  secondFactor: SecondFactorStateId;
}

function linkedMinecraftAccountOf(profile: PanelProfileResponse): LinkedMinecraftAccount | null {
  return profile.minecraftUuid ? { uuid: profile.minecraftUuid, username: profile.minecraftUsername } : null;
}

function toSignedInUser(profile: PanelProfileResponse): SignedInUser {
  return {
    id: profile.id ?? '',
    email: profile.email,
    username: profile.username,
    role: profile.role,
    minecraftAccount: linkedMinecraftAccountOf(profile),
    ...optionalField('language', profile.language || undefined),
    dateFormat: profile.dateFormat || DEFAULT_DATE_FORMAT,
    secondFactor: toSecondFactorState(profile.secondFactorState),
  };
}

const SIGNED_OUT_STATUSES: ReadonlySet<number> = new Set([HTTP_UNAUTHORIZED, HTTP_STAFF_NOT_FOUND]);

function isSignedOutAnswer(error: unknown): boolean {
  return error instanceof ApiHttpError && SIGNED_OUT_STATUSES.has(error.status);
}

async function fetchSignedInUser({ signal }: { signal: AbortSignal }): Promise<SignedInUser | null> {
  try {
    return toSignedInUser(await protoFetch(PanelProfileResponseSchema, SIGNED_IN_PROFILE_PATH, { signal }));
  } catch (error) {
    if (isSignedOutAnswer(error)) return null;
    throw error;
  }
}

const signedInProfileQuery = queryOptions({
  queryKey: SIGNED_IN_PROFILE_QUERY_KEY,
  queryFn: fetchSignedInUser,
  staleTime: Infinity,
  ...RATE_LIMIT_AWARE_RETRY,
});

export function useSignedInProfile() {
  return useQuery(signedInProfileQuery);
}

export function useMinecraftLinkPolling(polling: boolean) {
  return useQuery({
    ...signedInProfileQuery,
    refetchInterval: polling ? MINECRAFT_LINK_POLL_INTERVAL_MS : false,
  });
}

export async function refetchSignedInProfile(queryClient: QueryClient): Promise<SignedInUser | null> {
  await queryClient.cancelQueries({ queryKey: SIGNED_IN_PROFILE_QUERY_KEY, exact: true });
  return queryClient.query({ ...signedInProfileQuery, staleTime: 0 });
}

function outlivesSignOut(queryKey: QueryKey): boolean {
  return queryKey[0] === SIGNED_IN_PROFILE_PATH || isPublicQueryKey(queryKey);
}

export function markSignedOut(queryClient: QueryClient): void {
  void queryClient.cancelQueries({ queryKey: SIGNED_IN_PROFILE_QUERY_KEY, exact: true });
  queryClient.removeQueries({ predicate: (query) => !outlivesSignOut(query.queryKey) });
  queryClient.getMutationCache().clear();
  queryClient.setQueryData(SIGNED_IN_PROFILE_QUERY_KEY, null);
}

export function isSignedInEmail(queryClient: QueryClient, email: string): boolean {
  const user = queryClient.getQueryData<SignedInUser | null>(SIGNED_IN_PROFILE_QUERY_KEY);
  return user?.email.toLowerCase() === email.toLowerCase();
}

export function forgetMinecraftAccount(queryClient: QueryClient): void {
  queryClient.setQueryData<SignedInUser | null>(SIGNED_IN_PROFILE_QUERY_KEY, (user) => user && { ...user, minecraftAccount: null });
}

export async function invalidateSignedInProfile(queryClient: QueryClient): Promise<void> {
  await queryClient.invalidateQueries({ queryKey: SIGNED_IN_PROFILE_QUERY_KEY, exact: true });
}
