import { z } from 'zod';
import type { PublicKeyCredentialCreationOptionsJSON, PublicKeyCredentialRequestOptionsJSON } from '@simplewebauthn/browser';

const CANCELLATION_ERROR_NAMES: readonly string[] = ['NotAllowedError', 'AbortError'];

export function isWebAuthnCancellation(e: unknown): boolean {
  return e instanceof Error && CANCELLATION_ERROR_NAMES.includes(e.name);
}

const userVerificationSchema = z.enum(['discouraged', 'preferred', 'required']);

const hintsSchema = z.array(z.enum(['hybrid', 'security-key', 'client-device']));

const credentialDescriptorSchema = z.object({
  id: z.string(),
  type: z.string(),
  transports: z.array(z.string()).exactOptional(),
});

const extensionsSchema = z.looseObject({
  appid: z.string().exactOptional(),
  credProps: z.boolean().exactOptional(),
  hmacCreateSecret: z.boolean().exactOptional(),
  minPinLength: z.boolean().exactOptional(),
});

const requestOptionsSchema = z.object({
  challenge: z.string().min(1),
  timeout: z.number().exactOptional(),
  rpId: z.string().exactOptional(),
  allowCredentials: z.array(credentialDescriptorSchema).exactOptional(),
  userVerification: userVerificationSchema.exactOptional(),
  hints: hintsSchema.exactOptional(),
  extensions: extensionsSchema.exactOptional(),
});

const creationOptionsSchema = z.object({
  rp: z.object({ name: z.string(), id: z.string().exactOptional() }),
  user: z.object({ id: z.string().min(1), name: z.string(), displayName: z.string() }),
  challenge: z.string().min(1),
  pubKeyCredParams: z.array(z.object({ alg: z.number(), type: z.literal('public-key') })),
  timeout: z.number().exactOptional(),
  excludeCredentials: z.array(credentialDescriptorSchema).exactOptional(),
  authenticatorSelection: z.object({
    authenticatorAttachment: z.enum(['cross-platform', 'platform']).exactOptional(),
    requireResidentKey: z.boolean().exactOptional(),
    residentKey: z.enum(['discouraged', 'preferred', 'required']).exactOptional(),
    userVerification: userVerificationSchema.exactOptional(),
  }).exactOptional(),
  hints: hintsSchema.exactOptional(),
  attestation: z.enum(['direct', 'enterprise', 'indirect', 'none']).exactOptional(),
  attestationFormats: z.array(z.enum(['fido-u2f', 'packed', 'android-safetynet', 'android-key', 'tpm', 'apple', 'none'])).exactOptional(),
  extensions: extensionsSchema.exactOptional(),
});

export const publicKeyRequestOptionsSchema: z.ZodType<PublicKeyCredentialRequestOptionsJSON> = z.union([
  z.object({ publicKey: requestOptionsSchema }).transform(({ publicKey }) => publicKey),
  requestOptionsSchema,
]);

export const publicKeyCreationOptionsSchema: z.ZodType<PublicKeyCredentialCreationOptionsJSON> = z.union([
  z.object({ publicKey: creationOptionsSchema }).transform(({ publicKey }) => publicKey),
  creationOptionsSchema,
]);
