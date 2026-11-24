import { useCallback, useMemo } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { create } from '@bufbuild/protobuf';
import {
  ConfirmUploadRequestSchema,
  MediaConfigResponseSchema,
  PresignUploadRequestSchema,
  PresignUploadResponseSchema,
  UploadResponseSchema,
  type MediaConfigResponse,
  type PresignUploadResponse,
  type UploadResponse,
} from '@proto/modl/v1/storage_pb.ts';
import { protoFetch, protoSend, ProtoHttpError } from '@/lib/proto-fetch';
import { describeError } from '@/lib/http-error';
import { toNum } from '@/lib/proto-ui';
import { uploadObject, type UploadProgress } from '@/lib/object-upload';
import { createMediaTrustPolicy, isTrustedMediaUrl, type MediaTrustPolicy } from '@/utils/evidence-utils';
import { isPublicPage } from '@/utils/routes';
import { readPublicRecordToken } from '@/lib/public-record-access';
import i18n from '@/lib/i18n';

export interface MediaUploadConfig {
  storageConfigured: boolean;
  publicBaseUrl: string;
  supportedTypes: Record<string, string[]>;
  fileSizeLimits: Record<string, number>;
}

type UploadMetadata = Readonly<Record<string, unknown>>;

const ENTITY_ID_KEYS = ['entityId', 'ticketId', 'appealId'] as const;
const ACCESS_TOKEN_KEYS = ['accessToken', 'ticketToken'] as const;

const MEDIA_CONFIG_QUERY_KEY = ['/v1/media/config'];
const PANEL_MEDIA_CONFIG_PATH = '/v1/panel/media/config';
const PUBLIC_MEDIA_CONFIG_PATH = '/v1/public/media/config';

function toMediaUploadConfig(response: MediaConfigResponse): MediaUploadConfig {
  return {
    storageConfigured: response.storageConfigured,
    publicBaseUrl: response.publicBaseUrl,
    supportedTypes: Object.fromEntries(
      Object.entries(response.supportedTypes).map(([category, supported]) => [category, supported.types]),
    ),
    fileSizeLimits: Object.fromEntries(
      Object.entries(response.fileSizeLimits).map(([category, limit]) => [category, toNum(limit)]),
    ),
  };
}

async function fetchMediaConfig(): Promise<MediaUploadConfig> {
  const path = isPublicPage() ? PUBLIC_MEDIA_CONFIG_PATH : PANEL_MEDIA_CONFIG_PATH;
  return toMediaUploadConfig(await protoFetch(MediaConfigResponseSchema, path));
}

export function useMediaUploadConfig() {
  return useQuery<MediaUploadConfig>({
    queryKey: MEDIA_CONFIG_QUERY_KEY,
    queryFn: fetchMediaConfig,
    staleTime: 5 * 60 * 1000,
    refetchOnWindowFocus: false,
  });
}

export function useMediaTrustPolicy(): MediaTrustPolicy {
  const { data } = useMediaUploadConfig();
  const publicBaseUrl = data?.publicBaseUrl;
  return useMemo(() => createMediaTrustPolicy(window.location.origin, publicBaseUrl), [publicBaseUrl]);
}

export function useTrustedMediaUrl(): (url?: string | null) => boolean {
  const policy = useMediaTrustPolicy();
  return useCallback((url?: string | null) => isTrustedMediaUrl(url, policy), [policy]);
}

function getEndpointPrefix(): string {
  return isPublicPage() ? '/v1/public/media' : '/v1/panel/media';
}

function firstText(metadata: UploadMetadata, keys: readonly string[]): string | undefined {
  for (const key of keys) {
    const value = metadata[key];
    if (typeof value === 'string' && value.length > 0) return value;
  }
  return undefined;
}

function resolveUploadContext(metadata: UploadMetadata): { entityId: string | undefined; accessToken: string | undefined } {
  const entityId = firstText(metadata, ENTITY_ID_KEYS)?.trim() || undefined;

  let accessToken = firstText(metadata, ACCESS_TOKEN_KEYS);
  if (!accessToken && entityId) {
    const normalized = entityId.toLowerCase();
    if (normalized !== 'new' && normalized !== 'unknown') {
      accessToken = readPublicRecordToken(entityId) ?? undefined;
    }
  }

  return { entityId, accessToken };
}

async function withMediaErrorMessage<T>(request: Promise<T>, fallbackKey: string): Promise<T> {
  try {
    return await request;
  } catch (error) {
    if (error instanceof ProtoHttpError) {
      throw new Error(describeError(error, i18n.t(fallbackKey)), { cause: error });
    }
    throw error;
  }
}

async function getPresignedUrl(file: File, uploadType: string, metadata: UploadMetadata): Promise<PresignUploadResponse> {
  const context = resolveUploadContext(metadata);
  const request = create(PresignUploadRequestSchema, {
    uploadType,
    fileName: file.name,
    fileSize: BigInt(file.size),
    contentType: file.type,
    entityId: context.entityId,
    accessToken: context.accessToken,
  });
  return withMediaErrorMessage(
    protoSend('POST', `${getEndpointPrefix()}/presign`, PresignUploadRequestSchema, request, PresignUploadResponseSchema),
    'upload.uploadFailed',
  );
}

async function confirmUpload(key: string, metadata: UploadMetadata): Promise<UploadResponse> {
  const { accessToken } = resolveUploadContext(metadata);
  const request = create(ConfirmUploadRequestSchema, { key, accessToken });
  return withMediaErrorMessage(
    protoSend('POST', `${getEndpointPrefix()}/confirm`, ConfirmUploadRequestSchema, request, UploadResponseSchema),
    'upload.uploadFailed',
  );
}

export function useMediaUpload() {
  const config = useMediaUploadConfig();
  const queryClient = useQueryClient();

  const uploadMedia = async (
    file: File,
    uploadType: 'evidence' | 'ticket' | 'appeal' | 'article' | 'server-icon',
    metadata: UploadMetadata = {},
    onProgress?: (progress: UploadProgress) => void
  ): Promise<{ url: string; key: string }> => {
    let currentConfig = queryClient.getQueryData<MediaUploadConfig>(MEDIA_CONFIG_QUERY_KEY);

    if (!currentConfig?.storageConfigured) {
      currentConfig = await fetchMediaConfig();
      queryClient.setQueryData(MEDIA_CONFIG_QUERY_KEY, currentConfig);
    }

    if (!currentConfig.storageConfigured) {
      throw new Error(i18n.t('upload.storageUnavailable'));
    }

    const presign = await getPresignedUrl(file, uploadType, metadata);

    await uploadObject(
      { url: presign.presignedUrl, method: presign.method, headers: presign.requiredHeaders },
      file,
      onProgress,
    );

    const confirmed = await confirmUpload(presign.key, metadata);

    return { url: confirmed.url, key: confirmed.key };
  };

  return {
    config: config.data,
    isConfigLoading: config.isLoading,
    configError: config.error,
    uploadMedia,
  };
}
