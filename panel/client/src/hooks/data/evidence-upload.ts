import { skipToken, useMutation, useQuery } from '@tanstack/react-query';
import { z } from 'zod';
import { apiFetch } from '@/lib/api';
import { apiErrorBodyMessage, parseJsonOrNull } from '@/lib/http-error';
import i18n from '@/lib/i18n';
import { uploadObject, type UploadProgress } from '@/lib/object-upload';

export interface EvidenceUploadTokenInfo {
  punishmentId: string;
  playerName: string;
  issuerName: string;
  maxUploadBytes: number;
}

export interface UploadedEvidence {
  url: string;
  key: string;
}

export interface EvidenceSubmission {
  url: string;
  fileName: string;
  fileType: string;
  fileSize: number;
}

export class EvidenceUploadError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'EvidenceUploadError';
  }
}

const tokenInfoSchema = z.object({
  punishmentId: z.string(),
  playerName: z.string(),
  issuerName: z.string(),
  maxUploadBytes: z.number().int().nonnegative(),
});

const presignSchema = z.object({
  presignedUrl: z.string().min(1),
  key: z.string().min(1),
  method: z.string().optional(),
  requiredHeaders: z.record(z.string(), z.string()).optional(),
});

const confirmSchema = z.object({
  url: z.string().min(1),
  key: z.string().min(1),
});

function evidenceUploadPath(token: string, action = ''): string {
  return `/v1/public/evidence-upload/${encodeURIComponent(token)}${action}`;
}

async function readEvidenceResponse<Schema extends z.ZodType>(
  response: Response,
  schema: Schema,
  fallbackMessage: string,
): Promise<z.infer<Schema>> {
  const bodyText = await response.text().catch(() => '');
  if (!response.ok) {
    throw new EvidenceUploadError(apiErrorBodyMessage(bodyText) ?? fallbackMessage);
  }
  const parsed = schema.safeParse(parseJsonOrNull(bodyText));
  if (!parsed.success) {
    throw new EvidenceUploadError(fallbackMessage);
  }
  return parsed.data;
}

async function fetchEvidenceUploadToken(token: string): Promise<EvidenceUploadTokenInfo> {
  const response = await apiFetch(evidenceUploadPath(token)).catch(() => {
    throw new EvidenceUploadError(i18n.t('evidence.validateFailed'));
  });
  return readEvidenceResponse(response, tokenInfoSchema, i18n.t('evidence.invalidToken'));
}

export function useEvidenceUploadToken(token: string) {
  return useQuery({
    queryKey: [evidenceUploadPath(token)],
    queryFn: token === '' ? skipToken : async () => fetchEvidenceUploadToken(token),
    retry: false,
    staleTime: Infinity,
    refetchOnWindowFocus: false,
  });
}

async function presignEvidenceUpload(token: string, file: File) {
  const response = await apiFetch(evidenceUploadPath(token, '/presign'), {
    method: 'POST',
    body: { fileName: file.name, contentType: file.type, fileSize: file.size },
  });
  return readEvidenceResponse(response, presignSchema, i18n.t('evidence.presignFailed'));
}

async function confirmEvidenceUpload(token: string, key: string): Promise<UploadedEvidence> {
  const response = await apiFetch(evidenceUploadPath(token, '/confirm'), {
    method: 'POST',
    body: { key },
  });
  return readEvidenceResponse(response, confirmSchema, i18n.t('evidence.confirmFailed'));
}

export async function uploadEvidenceFile(
  token: string,
  file: File,
  onProgress: (progress: UploadProgress) => void,
): Promise<UploadedEvidence> {
  const presigned = await presignEvidenceUpload(token, file);
  await uploadObject(
    { url: presigned.presignedUrl, method: presigned.method, headers: presigned.requiredHeaders },
    file,
    onProgress,
  );
  return confirmEvidenceUpload(token, presigned.key);
}

const submitSchema = z.object({});

async function submitEvidence(token: string, evidence: EvidenceSubmission[]): Promise<void> {
  const failedMessage = i18n.t('evidence.submitFailed');
  const response = await apiFetch(evidenceUploadPath(token, '/submit'), {
    method: 'POST',
    body: { evidence },
  }).catch(() => {
    throw new EvidenceUploadError(failedMessage);
  });
  await readEvidenceResponse(response, submitSchema, failedMessage);
}

export function useSubmitEvidence(token: string) {
  return useMutation({
    mutationFn: async (evidence: EvidenceSubmission[]) => submitEvidence(token, evidence),
  });
}
