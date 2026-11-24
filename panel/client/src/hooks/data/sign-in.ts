import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { create } from '@bufbuild/protobuf';
import { timestampDate } from '@bufbuild/protobuf/wkt';
import { PanelAuthResponseSchema, type PanelAuthResponse } from '@proto/modl/v1/auth_pb.ts';
import {
  AccountSecurityResponseSchema,
  CompleteSecondFactorRequestSchema,
  ConfirmTotpRequestSchema,
  DisableTotpRequestSchema,
  InspectInvitationRequestSchema,
  InspectInvitationResponseSchema,
  InspectPasswordResetRequestSchema,
  InspectPasswordResetResponseSchema,
  PanelPasswordLoginRequestSchema,
  RecoveryCodesResponseSchema,
  RegenerateRecoveryCodesRequestSchema,
  ResetPasswordRequestSchema,
  SecondFactorMethod,
  SecondFactorState,
  SetPasswordRequestSchema,
  SignInMethod,
  SignInSettingsResponseSchema,
  StartTotpEnrollmentRequestSchema,
  TotpEnrollmentResponseSchema,
  UpdateSignInSettingsRequestSchema,
  type AccountSecurityResponse,
  type SignInSettingsResponse,
} from '@proto/modl/v1/sign_in_pb.ts';
import { protoFetch, protoSend } from '@/lib/proto-fetch';
import { optionalField } from '@/lib/defined-fields';
import { PUBLIC_SETTINGS_QUERY_KEY } from '@/lib/public-queries';
import type { SecondFactorOption, SignInChallenge } from '@/lib/sign-in-outcome';

export type SignInMethodId = 'email-code' | 'password';
export type SecondFactorStateId = 'satisfied' | 'enrollment-required' | 'verification-required';

export interface CredentialProofInput {
  currentPassword?: string;
  code?: string;
}

export interface AccountSecurity {
  method: SignInMethodId;
  hasPassword: boolean;
  totpEnabled: boolean;
  totpEnabledAt?: string;
  recoveryCodesRemaining: number;
  passkeyCount: number;
  secondFactorRequired: boolean;
  state: SecondFactorStateId;
}

export interface TotpEnrollment {
  secret: string;
  keyUri: string;
}

export interface SignInSettings {
  method: SignInMethodId;
  secondFactorRequired: boolean;
  version: number;
  withoutPasswordCount: number;
  withoutSecondFactorCount: number;
}

export interface SignInSettingsUpdate {
  expectedVersion: number;
  method: SignInMethodId;
  secondFactorRequired: boolean;
}

export const ACCOUNT_SECURITY_QUERY_KEY = ['/v1/panel/auth/security'] as const;
export const SIGN_IN_SETTINGS_QUERY_KEY = ['/v1/panel/settings/sign-in'] as const;
const CALLER_RENDERS_LIMIT = { rateLimitFeedback: 'caller' } as const;

const METHODS: ReadonlyMap<SignInMethod, SignInMethodId> = new Map<SignInMethod, SignInMethodId>([
  [SignInMethod.UNSPECIFIED, 'email-code'],
  [SignInMethod.EMAIL_CODE, 'email-code'],
  [SignInMethod.PASSWORD, 'password'],
]);

export const SIGN_IN_METHOD_VALUES: Readonly<Record<SignInMethodId, SignInMethod>> = {
  'email-code': SignInMethod.EMAIL_CODE,
  password: SignInMethod.PASSWORD,
};

const STATES: ReadonlyMap<SecondFactorState, SecondFactorStateId> = new Map<SecondFactorState, SecondFactorStateId>([
  [SecondFactorState.UNSPECIFIED, 'satisfied'],
  [SecondFactorState.SATISFIED, 'satisfied'],
  [SecondFactorState.ENROLLMENT_REQUIRED, 'enrollment-required'],
  [SecondFactorState.VERIFICATION_REQUIRED, 'verification-required'],
]);

const OPTIONS: ReadonlyMap<SecondFactorMethod, SecondFactorOption | null> = new Map<SecondFactorMethod, SecondFactorOption | null>([
  [SecondFactorMethod.UNSPECIFIED, null],
  [SecondFactorMethod.TOTP, 'totp'],
  [SecondFactorMethod.RECOVERY_CODE, 'recovery-code'],
  [SecondFactorMethod.PASSKEY, 'passkey'],
]);

export function toSignInMethod(method: SignInMethod): SignInMethodId {
  return METHODS.get(method) ?? 'email-code';
}

export function toSecondFactorState(state: SecondFactorState): SecondFactorStateId {
  return STATES.get(state) ?? 'satisfied';
}

export function toSignInChallenge(response: PanelAuthResponse): SignInChallenge | null {
  const pending = response.secondFactor;
  if (!pending) return null;
  return {
    challenge: pending.challenge,
    email: pending.email,
    options: pending.methods
      .map((method) => OPTIONS.get(method) ?? null)
      .filter((option): option is SecondFactorOption => option !== null),
  };
}

function toAccountSecurity(response: AccountSecurityResponse): AccountSecurity {
  return {
    method: toSignInMethod(response.method),
    hasPassword: response.hasPassword,
    totpEnabled: response.totpEnabled,
    ...optionalField('totpEnabledAt', response.totpEnabledAt ? timestampDate(response.totpEnabledAt).toISOString() : undefined),
    recoveryCodesRemaining: response.recoveryCodesRemaining,
    passkeyCount: response.passkeyCount,
    secondFactorRequired: response.secondFactorRequired,
    state: toSecondFactorState(response.state),
  };
}

function toSignInSettings(response: SignInSettingsResponse): SignInSettings {
  return {
    method: toSignInMethod(response.method),
    secondFactorRequired: response.secondFactorRequired,
    version: Number(response.version),
    withoutPasswordCount: response.withoutPasswordCount,
    withoutSecondFactorCount: response.withoutSecondFactorCount,
  };
}

export async function sendPasswordLogin(email: string, password: string): Promise<PanelAuthResponse> {
  return protoSend(
    'POST',
    '/v1/panel/auth/password/login',
    PanelPasswordLoginRequestSchema,
    create(PanelPasswordLoginRequestSchema, { email, password }),
    PanelAuthResponseSchema,
    CALLER_RENDERS_LIMIT,
  );
}

export async function sendSecondFactorCode(challenge: string, code: string): Promise<PanelAuthResponse> {
  return protoSend(
    'POST',
    '/v1/panel/auth/sign-in/second-factor',
    CompleteSecondFactorRequestSchema,
    create(CompleteSecondFactorRequestSchema, { challenge, code }),
    PanelAuthResponseSchema,
    CALLER_RENDERS_LIMIT,
  );
}

export function useAccountSecurity() {
  return useQuery({
    queryKey: ACCOUNT_SECURITY_QUERY_KEY,
    queryFn: async () => toAccountSecurity(await protoFetch(AccountSecurityResponseSchema, '/v1/panel/auth/security')),
  });
}

export function useSetPassword() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ newPassword, proof }: { newPassword: string; proof: CredentialProofInput }) => toAccountSecurity(await protoSend(
      'PUT',
      '/v1/panel/auth/password',
      SetPasswordRequestSchema,
      create(SetPasswordRequestSchema, { newPassword, proof }),
      AccountSecurityResponseSchema,
      CALLER_RENDERS_LIMIT,
    )),
    onSuccess: (security) => {
      queryClient.setQueryData(ACCOUNT_SECURITY_QUERY_KEY, security);
      void queryClient.invalidateQueries({ queryKey: SIGN_IN_SETTINGS_QUERY_KEY });
    },
  });
}

export function useStartTotpEnrollment() {
  return useMutation({
    mutationFn: async (proof: CredentialProofInput): Promise<TotpEnrollment> => {
      const response = await protoSend(
        'POST',
        '/v1/panel/auth/totp',
        StartTotpEnrollmentRequestSchema,
        create(StartTotpEnrollmentRequestSchema, { proof }),
        TotpEnrollmentResponseSchema,
        CALLER_RENDERS_LIMIT,
      );
      return { secret: response.secret, keyUri: response.keyUri };
    },
  });
}

export function useConfirmTotp() {
  return useMutation({
    mutationFn: async (code: string) => (await protoSend(
      'POST',
      '/v1/panel/auth/totp/confirm',
      ConfirmTotpRequestSchema,
      create(ConfirmTotpRequestSchema, { code }),
      RecoveryCodesResponseSchema,
      CALLER_RENDERS_LIMIT,
    )).codes,
  });
}

export function useDisableTotp() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (proof: CredentialProofInput) => toAccountSecurity(await protoSend(
      'POST',
      '/v1/panel/auth/totp/disable',
      DisableTotpRequestSchema,
      create(DisableTotpRequestSchema, { proof }),
      AccountSecurityResponseSchema,
      CALLER_RENDERS_LIMIT,
    )),
    onSuccess: (security) => {
      queryClient.setQueryData(ACCOUNT_SECURITY_QUERY_KEY, security);
      void queryClient.invalidateQueries({ queryKey: SIGN_IN_SETTINGS_QUERY_KEY });
    },
  });
}

export function useRegenerateRecoveryCodes() {
  return useMutation({
    mutationFn: async (proof: CredentialProofInput) => (await protoSend(
      'POST',
      '/v1/panel/auth/totp/recovery-codes',
      RegenerateRecoveryCodesRequestSchema,
      create(RegenerateRecoveryCodesRequestSchema, { proof }),
      RecoveryCodesResponseSchema,
      CALLER_RENDERS_LIMIT,
    )).codes,
  });
}

export function usePasswordResetInspection(token: string) {
  return useQuery({
    queryKey: ['/v1/panel/auth/password/reset/inspect', token],
    enabled: token.length > 0,
    queryFn: async () => {
      const response = await protoSend(
        'POST',
        '/v1/panel/auth/password/reset/inspect',
        InspectPasswordResetRequestSchema,
        create(InspectPasswordResetRequestSchema, { token }),
        InspectPasswordResetResponseSchema,
        CALLER_RENDERS_LIMIT,
      );
      return { email: response.email };
    },
    retry: false,
    refetchOnWindowFocus: false,
    staleTime: Infinity,
  });
}

export function useResetPassword() {
  return useMutation({
    mutationFn: async ({ token, password }: { token: string; password: string }) => {
      await protoSend(
        'POST',
        '/v1/panel/auth/password/reset',
        ResetPasswordRequestSchema,
        create(ResetPasswordRequestSchema, { token, password }),
        PanelAuthResponseSchema,
        CALLER_RENDERS_LIMIT,
      );
    },
  });
}

export function useInvitationInspection(token: string, enabled: boolean) {
  return useQuery({
    queryKey: ['/v1/public/staff/invitations/inspect', token],
    enabled: enabled && token.length > 0,
    queryFn: async () => {
      const response = await protoSend(
        'POST',
        '/v1/public/staff/invitations/inspect',
        InspectInvitationRequestSchema,
        create(InspectInvitationRequestSchema, { token }),
        InspectInvitationResponseSchema,
        CALLER_RENDERS_LIMIT,
      );
      return { email: response.email, method: toSignInMethod(response.method) };
    },
    retry: false,
    refetchOnWindowFocus: false,
    staleTime: Infinity,
  });
}

export function useSignInSettings() {
  return useQuery({
    queryKey: SIGN_IN_SETTINGS_QUERY_KEY,
    queryFn: async () => toSignInSettings(await protoFetch(SignInSettingsResponseSchema, '/v1/panel/settings/sign-in')),
  });
}

export function useUpdateSignInSettings() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ expectedVersion, method, secondFactorRequired }: SignInSettingsUpdate) => toSignInSettings(await protoSend(
      'PUT',
      '/v1/panel/settings/sign-in',
      UpdateSignInSettingsRequestSchema,
      create(UpdateSignInSettingsRequestSchema, {
        expectedVersion: BigInt(expectedVersion),
        method: SIGN_IN_METHOD_VALUES[method],
        secondFactorRequired,
      }),
      SignInSettingsResponseSchema,
    )),
    onSuccess: (settings) => {
      queryClient.setQueryData(SIGN_IN_SETTINGS_QUERY_KEY, settings);
      void queryClient.invalidateQueries({ queryKey: PUBLIC_SETTINGS_QUERY_KEY });
      void queryClient.invalidateQueries({ queryKey: ACCOUNT_SECURITY_QUERY_KEY });
    },
    onError: async () => queryClient.invalidateQueries({ queryKey: SIGN_IN_SETTINGS_QUERY_KEY }),
  });
}
