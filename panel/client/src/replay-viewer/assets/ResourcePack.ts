import { bareName } from '../format/ids.ts';
import { parseJsonBytes } from './json.ts';
import { legacyTextureName } from './legacyTextureNames.ts';

export type Files = ReadonlyMap<string, Uint8Array<ArrayBuffer>>;

export interface ResourcePackSource {
  readonly version: string;
  readonly primary: Files;
  readonly models: Files | null;
}

export class ResourcePack {
  readonly version: string;
  readonly legacy: boolean;
  private readonly primary: Files;
  private readonly models: Files;
  private readonly jsonCache = new Map<string, unknown>();

  constructor(source: ResourcePackSource) {
    this.version = source.version;
    this.primary = source.primary;
    this.legacy = source.models !== null;
    this.models = source.models ?? source.primary;
  }

  blockstate(blockName: string): unknown {
    return this.modelJson(`blockstates/${bareName(blockName)}.json`);
  }

  model(modelId: string): unknown {
    return this.modelJson(`models/${bareName(modelId)}.json`);
  }

  itemDefinition(itemName: string): unknown {
    return this.modelJson(`items/${bareName(itemName)}.json`);
  }

  hasItemDefinitions(): boolean {
    return this.models.has('items/stone.json');
  }

  equipment(assetId: string): unknown {
    return this.json(`equipment/${bareName(assetId)}.json`);
  }

  texturePath(textureId: string): string | null {
    const id = bareName(textureId);
    if (this.legacy) {
      const legacyPath = `textures/${legacyTextureName(id)}.png`;
      if (this.primary.has(legacyPath)) return legacyPath;
    }
    const path = `textures/${id}.png`;
    if (this.primary.has(path)) return path;
    return null;
  }

  texture(textureId: string): Uint8Array<ArrayBuffer> | null {
    const path = this.texturePath(textureId);
    if (path !== null) return this.primary.get(path) ?? null;
    if (this.legacy) return this.models.get(`textures/${bareName(textureId)}.png`) ?? null;
    return null;
  }

  textureMetadata(textureId: string): unknown {
    const path = this.texturePath(textureId);
    if (path !== null) return this.json(`${path}.mcmeta`);
    if (this.legacy) return this.modelJson(`textures/${bareName(textureId)}.png.mcmeta`);
    return null;
  }

  file(path: string): Uint8Array<ArrayBuffer> | null {
    return this.primary.get(path) ?? null;
  }

  json(path: string): unknown {
    return this.cachedJson(this.primary, `p:${path}`, path);
  }

  paths(prefix: string): string[] {
    return [...this.primary.keys()].filter((path) => path.startsWith(prefix));
  }

  private modelJson(path: string): unknown {
    return this.cachedJson(this.models, `m:${path}`, path);
  }

  private cachedJson(files: Files, key: string, path: string): unknown {
    if (this.jsonCache.has(key)) return this.jsonCache.get(key);
    const bytes = files.get(path);
    const value = bytes === undefined ? null : parseJsonBytes(bytes);
    this.jsonCache.set(key, value);
    return value;
  }
}
