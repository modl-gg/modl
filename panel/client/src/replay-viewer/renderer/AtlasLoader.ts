import { z } from 'zod';
import type { AtlasMapping } from '../types/AtlasMapping';
import { resolveAtlasVersion } from '../engine/VersionResolver';
import { atlasMappingSchema } from './AtlasData';

const versionManifestSchema = z.object({
  versions: z.array(z.string()),
  default: z.string(),
});

export interface AtlasRequest {
  readonly atlasUrl: string | undefined;
  readonly atlasMappingUrl: string | undefined;
  readonly atlasBaseUrl: string;
  readonly mcVersion: string;
  readonly signal: AbortSignal;
}

export interface LoadedAtlas {
  readonly imageUrl: string;
  readonly mapping: AtlasMapping;
}

interface AtlasSource {
  readonly imageUrl: string;
  readonly mappingUrl: string;
}

async function fetchValidatedJson<T>(url: string, schema: z.ZodType<T>, signal: AbortSignal): Promise<T | null> {
  try {
    const response = await fetch(url, { signal });
    if (!response.ok) return null;
    const parsed = schema.safeParse(await response.json());
    return parsed.success ? parsed.data : null;
  } catch (error) {
    signal.throwIfAborted();
    if (error instanceof TypeError || error instanceof SyntaxError) return null;
    throw error;
  }
}

async function resolveAtlasSource(request: AtlasRequest): Promise<AtlasSource | null> {
  const { atlasUrl, atlasMappingUrl, atlasBaseUrl } = request;
  if (atlasUrl && atlasMappingUrl) {
    return { imageUrl: atlasUrl, mappingUrl: atlasMappingUrl };
  }
  const manifest = await fetchValidatedJson(`${atlasBaseUrl}/versions.json`, versionManifestSchema, request.signal);
  if (!manifest) return null;
  const version = resolveAtlasVersion(request.mcVersion, manifest);
  return {
    imageUrl: atlasUrl || `${atlasBaseUrl}/${version}/atlas.png`,
    mappingUrl: atlasMappingUrl || `${atlasBaseUrl}/${version}/atlas-data.json`,
  };
}

export async function loadAtlas(request: AtlasRequest): Promise<LoadedAtlas | null> {
  const source = await resolveAtlasSource(request);
  if (!source) return null;
  const mapping = await fetchValidatedJson(source.mappingUrl, atlasMappingSchema, request.signal);
  return mapping ? { imageUrl: source.imageUrl, mapping } : null;
}
