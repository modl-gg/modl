import { useMutation, useQuery } from '@tanstack/react-query';
import { create } from '@bufbuild/protobuf';
import { z } from 'zod';
import { startRegistration } from '@simplewebauthn/browser';
import {
  RenameWebAuthnCredentialRequestSchema,
  WebAuthnCredentialMutationResponseSchema,
  WebAuthnCredentialsResponseSchema,
  type WebAuthnCredentialMetadata,
} from '@proto/modl/v1/webauthn_pb.ts';
import { apiFetch } from '@/lib/api';
import { ApiHttpError } from '@/lib/http-error';
import { protoFetch, protoHttpErrorOf, protoSend } from '@/lib/proto-fetch';
import { tsToIso } from '@/lib/proto-ui';
import { isWebAuthnCancellation, publicKeyCreationOptionsSchema } from '@/utils/webauthn';
import { useInvalidatingMutation } from '@/hooks/data/invalidating-mutation';
import type { CredentialProofInput } from '@/hooks/data/sign-in';

const WEBAUTHN_PATH = '/v1/panel/auth/webauthn';
const CREDENTIALS_PATH = `${WEBAUTHN_PATH}/credentials`;
const PASSKEY_CREDENTIALS_QUERY_KEY = [CREDENTIALS_PATH] as const;

export interface PasskeyCredential {
  id: string;
  name: string;
  createdAt: string | null;
  lastUsedAt: string | null;
}

export interface PendingPasskeyRegistration {
  challengeId: string;
  response: string;
}

export interface PasskeyRegistration extends PendingPasskeyRegistration {
  name: string;
}

export interface PasskeyRename {
  credentialId: string;
  name: string;
}

const registrationOptionsSchema = z.object({
  challengeId: z.string().min(1),
  options: publicKeyCreationOptionsSchema,
});

const credentialPath = (credentialId: string): string => `${CREDENTIALS_PATH}/${encodeURIComponent(credentialId)}`;

function mapCredential(credential: WebAuthnCredentialMetadata): PasskeyCredential {
  return {
    id: credential.id,
    name: credential.name,
    createdAt: tsToIso(credential.createdAt),
    lastUsedAt: tsToIso(credential.lastUsedAt),
  };
}

async function postJson(path: string, body?: unknown): Promise<unknown> {
  const response = await apiFetch(path, { method: 'POST', body });
  if (!response.ok) {
    throw await protoHttpErrorOf(response);
  }
  const parsed: unknown = await response.json();
  return parsed;
}

export function usePasskeyCredentials() {
  return useQuery({
    queryKey: PASSKEY_CREDENTIALS_QUERY_KEY,
    queryFn: async (): Promise<PasskeyCredential[]> => {
      const response = await protoFetch(WebAuthnCredentialsResponseSchema, CREDENTIALS_PATH);
      return response.credentials.map(mapCredential);
    },
    retry: false,
  });
}

export type PasskeyRegistrationFailure = 'cancelled' | 'not-started' | 'failed';

export function classifyPasskeyRegistrationError(error: unknown): PasskeyRegistrationFailure {
  if (isWebAuthnCancellation(error)) return 'cancelled';
  if (error instanceof ApiHttpError || error instanceof z.ZodError) return 'not-started';
  return 'failed';
}

export function useStartPasskeyRegistration() {
  return useMutation({
    mutationFn: async (proof: CredentialProofInput): Promise<PendingPasskeyRegistration> => {
      const { challengeId, options } = registrationOptionsSchema.parse(await postJson(`${WEBAUTHN_PATH}/register/options`, proof));
      const attestation = await startRegistration({ optionsJSON: options });
      return { challengeId, response: JSON.stringify(attestation) };
    },
  });
}

export function useFinishPasskeyRegistration() {
  return useInvalidatingMutation(PASSKEY_CREDENTIALS_QUERY_KEY, async (registration: PasskeyRegistration) =>
    postJson(`${WEBAUTHN_PATH}/register/verify`, registration));
}

export function useRenamePasskey() {
  return useInvalidatingMutation(PASSKEY_CREDENTIALS_QUERY_KEY, async ({ credentialId, name }: PasskeyRename) => protoSend(
    'PATCH',
    credentialPath(credentialId),
    RenameWebAuthnCredentialRequestSchema,
    create(RenameWebAuthnCredentialRequestSchema, { name }),
    WebAuthnCredentialMutationResponseSchema,
  ));
}

export function useDeletePasskey() {
  return useInvalidatingMutation(PASSKEY_CREDENTIALS_QUERY_KEY, async (credentialId: string) =>
    protoFetch(WebAuthnCredentialMutationResponseSchema, credentialPath(credentialId), { method: 'DELETE' }));
}
