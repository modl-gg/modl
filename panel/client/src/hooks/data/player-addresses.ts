import { create } from '@bufbuild/protobuf';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  PlayerAddressLookupTestOutcome,
  PlayerAddressLookupTestResponseSchema,
  PlayerAddressSettingsResponseSchema,
  SavePlayerAddressKeyRequestSchema,
  TestPlayerAddressLookupRequestSchema,
  UpdatePlayerAddressSettingsRequestSchema,
  type PlayerAddressLookupTestResponse,
  type PlayerAddressSettingsResponse,
} from '@proto/modl/v1/player_address_pb.ts';
import { optionalField } from '@/lib/defined-fields';
import { protoFetch, protoSend } from '@/lib/proto-fetch';
import { toNum, tsToIso } from '@/lib/proto-ui';

export const PLAYER_ADDRESS_SETTINGS_PATH = '/v1/panel/settings/player-addresses';
const PLAYER_ADDRESS_SETTINGS_QUERY_KEY = [PLAYER_ADDRESS_SETTINGS_PATH] as const;

export const KEYED_IP_LOOKUP_PROVIDERS = ['proxycheck', 'vpnapi', 'iphub', 'ipapi_is'] as const;
export const KEYLESS_IP_LOOKUP_PROVIDER = 'proxycheck_nokey';
export const IP_LOOKUP_PROVIDERS = [...KEYED_IP_LOOKUP_PROVIDERS, KEYLESS_IP_LOOKUP_PROVIDER] as const;
export type KeyedIpLookupProviderId = (typeof KEYED_IP_LOOKUP_PROVIDERS)[number];
export type IpLookupProviderId = (typeof IP_LOOKUP_PROVIDERS)[number];
export type LookupTestOutcome = 'working' | 'noVerdict' | 'refused' | 'dailyLimit' | 'unexpectedAnswer' | 'unreachable' | 'busy';
export type ForwardingNoticeReason = 'nonPublicAddress' | 'sharedAddress';

export interface ForwardingNotice {
  showing: boolean;
  reason: ForwardingNoticeReason | null;
  serverName: string | null;
  detectedAt: string | null;
}

export interface PlayerAddressSettings {
  provider: IpLookupProviderId | null;
  providersWithKeys: IpLookupProviderId[];
  lookupsActive: boolean;
  lookupChoicePending: boolean;
  sharedAccountLimit: number;
  minSharedAccountLimit: number;
  maxSharedAccountLimit: number;
  forwardingNotice: ForwardingNotice;
}

export interface LookupTestResult {
  outcome: LookupTestOutcome;
  testAddress: string;
  latencyMs: number;
  upstreamStatus: number | null;
  keyChecked: boolean;
}

export interface LookupTestRequest {
  provider: IpLookupProviderId;
  apiKey?: string;
}

export interface PlayerAddressSettingsUpdate {
  provider?: IpLookupProviderId | null;
  sharedAccountLimit?: number;
}

export function isProviderId(value: string): value is IpLookupProviderId {
  return IP_LOOKUP_PROVIDERS.some((provider) => provider === value);
}

export function isKeyedProvider(value: IpLookupProviderId): value is KeyedIpLookupProviderId {
  return KEYED_IP_LOOKUP_PROVIDERS.some((provider) => provider === value);
}

function providerOf(value: string | undefined): IpLookupProviderId | null {
  return value !== undefined && isProviderId(value) ? value : null;
}

function reasonOf(value: string): ForwardingNoticeReason | null {
  switch (value) {
    case 'NON_PUBLIC_ADDRESS':
      return 'nonPublicAddress';
    case 'SHARED_ADDRESS':
      return 'sharedAddress';
    default:
      return null;
  }
}

function toSettings(response: PlayerAddressSettingsResponse): PlayerAddressSettings {
  const notice = response.forwardingNotice;
  return {
    provider: providerOf(response.provider),
    providersWithKeys: response.providersWithKeys.filter(isProviderId),
    lookupsActive: response.lookupsActive,
    lookupChoicePending: response.lookupChoicePending,
    sharedAccountLimit: response.sharedAccountLimit,
    minSharedAccountLimit: response.minSharedAccountLimit,
    maxSharedAccountLimit: response.maxSharedAccountLimit,
    forwardingNotice: {
      showing: notice?.showing ?? false,
      reason: notice === undefined ? null : reasonOf(notice.reason),
      serverName: notice?.serverName ?? null,
      detectedAt: tsToIso(notice?.detectedAt),
    },
  };
}

function outcomeOf(outcome: PlayerAddressLookupTestOutcome): LookupTestOutcome {
  switch (outcome) {
    case PlayerAddressLookupTestOutcome.WORKING:
      return 'working';
    case PlayerAddressLookupTestOutcome.NO_VERDICT:
      return 'noVerdict';
    case PlayerAddressLookupTestOutcome.REFUSED:
      return 'refused';
    case PlayerAddressLookupTestOutcome.DAILY_LIMIT_REACHED:
      return 'dailyLimit';
    case PlayerAddressLookupTestOutcome.UNREACHABLE:
      return 'unreachable';
    case PlayerAddressLookupTestOutcome.BUSY:
      return 'busy';
    case PlayerAddressLookupTestOutcome.UNEXPECTED_ANSWER:
    case PlayerAddressLookupTestOutcome.UNSPECIFIED:
      return 'unexpectedAnswer';
  }
}

function toTestResult(response: PlayerAddressLookupTestResponse): LookupTestResult {
  return {
    outcome: outcomeOf(response.outcome),
    testAddress: response.testAddress,
    latencyMs: toNum(response.latencyMs),
    upstreamStatus: response.upstreamStatus ?? null,
    keyChecked: response.keyChecked,
  };
}

export function usePlayerAddressSettings(enabled: boolean) {
  return useQuery({
    queryKey: PLAYER_ADDRESS_SETTINGS_QUERY_KEY,
    queryFn: async () => toSettings(await protoFetch(PlayerAddressSettingsResponseSchema, PLAYER_ADDRESS_SETTINGS_PATH)),
    enabled,
    staleTime: 60_000,
    refetchOnWindowFocus: false,
  });
}

function usePlayerAddressMutation<Input>(send: (input: Input) => Promise<PlayerAddressSettingsResponse>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: Input) => toSettings(await send(input)),
    onSuccess: (settings) => {
      queryClient.setQueryData(PLAYER_ADDRESS_SETTINGS_QUERY_KEY, settings);
    },
  });
}

export function useUpdatePlayerAddressSettings() {
  return usePlayerAddressMutation(async ({ provider, sharedAccountLimit }: PlayerAddressSettingsUpdate) => protoSend(
    'PUT',
    PLAYER_ADDRESS_SETTINGS_PATH,
    UpdatePlayerAddressSettingsRequestSchema,
    create(UpdatePlayerAddressSettingsRequestSchema, {
      ...optionalField('provider', provider === undefined ? undefined : provider ?? ''),
      ...optionalField('sharedAccountLimit', sharedAccountLimit),
    }),
    PlayerAddressSettingsResponseSchema,
  ));
}

export function useSavePlayerAddressKey() {
  return usePlayerAddressMutation(async ({ provider, apiKey }: { provider: IpLookupProviderId; apiKey: string }) => protoSend(
    'PUT',
    `${PLAYER_ADDRESS_SETTINGS_PATH}/keys/${encodeURIComponent(provider)}`,
    SavePlayerAddressKeyRequestSchema,
    create(SavePlayerAddressKeyRequestSchema, { apiKey }),
    PlayerAddressSettingsResponseSchema,
  ));
}

export function useRemovePlayerAddressKey() {
  return usePlayerAddressMutation(async (provider: IpLookupProviderId) => protoFetch(
    PlayerAddressSettingsResponseSchema,
    `${PLAYER_ADDRESS_SETTINGS_PATH}/keys/${encodeURIComponent(provider)}`,
    { method: 'DELETE' },
  ));
}

export function useDismissForwardingNotice() {
  return usePlayerAddressMutation<undefined>(async () => protoFetch(
    PlayerAddressSettingsResponseSchema,
    `${PLAYER_ADDRESS_SETTINGS_PATH}/notice/dismiss`,
    { method: 'POST' },
  ));
}

export function useDismissLookupChoice() {
  return usePlayerAddressMutation<undefined>(async () => protoFetch(
    PlayerAddressSettingsResponseSchema,
    `${PLAYER_ADDRESS_SETTINGS_PATH}/choice/dismiss`,
    { method: 'POST' },
  ));
}

export function useTestPlayerAddressLookup() {
  return useMutation({
    mutationFn: async ({ provider, apiKey }: LookupTestRequest): Promise<LookupTestResult> => toTestResult(await protoSend(
      'POST',
      `${PLAYER_ADDRESS_SETTINGS_PATH}/test`,
      TestPlayerAddressLookupRequestSchema,
      create(TestPlayerAddressLookupRequestSchema, { provider, ...optionalField('apiKey', apiKey) }),
      PlayerAddressLookupTestResponseSchema,
    )),
  });
}
