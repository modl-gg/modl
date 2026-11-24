import { useMutation } from '@tanstack/react-query';
import { create } from '@bufbuild/protobuf';
import { ClaimSetupRequestSchema, SetupOwnerSignInRequestSchema } from '@proto/modl/v1/setup_pb.ts';
import { PanelAuthResponseSchema } from '@proto/modl/v1/auth_pb.ts';
import { protoSend } from '@/lib/proto-fetch';
import { normalizeSetupCode } from '@/lib/setup-code';

export interface SetupClaim {
  code: string;
  email: string;
}

const CALLER_RENDERS_COOLDOWN = { rateLimitFeedback: 'caller' } as const;

export function useClaimSetup() {
  return useMutation({
    mutationFn: async (claim: SetupClaim) => protoSend(
      'POST',
      '/v1/public/setup/claim',
      ClaimSetupRequestSchema,
      create(ClaimSetupRequestSchema, {
        code: normalizeSetupCode(claim.code),
        owner: { email: claim.email.trim() },
        publicUrl: window.location.origin,
      }),
      PanelAuthResponseSchema,
      CALLER_RENDERS_COOLDOWN,
    ),
  });
}

export function useOwnerSignInWithCode() {
  return useMutation({
    mutationFn: async (code: string) => protoSend(
      'POST',
      '/v1/public/setup/sign-in',
      SetupOwnerSignInRequestSchema,
      create(SetupOwnerSignInRequestSchema, { code: normalizeSetupCode(code) }),
      PanelAuthResponseSchema,
      CALLER_RENDERS_COOLDOWN,
    ),
  });
}
