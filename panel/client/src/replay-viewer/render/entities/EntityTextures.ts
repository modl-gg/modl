import { NearestFilter, NoColorSpace, Texture } from 'three';
import type { LoadedAssets } from '../../assets/loadAssets.ts';
import { normalizeSkin } from '../../assets/Skins.ts';

export interface LoadedTexture {
  readonly texture: Texture;
  readonly width: number;
  readonly height: number;
}

export function textureFrom(image: ImageBitmap | OffscreenCanvas): Texture {
  const texture = new Texture(image);
  texture.flipY = false;
  texture.magFilter = NearestFilter;
  texture.minFilter = NearestFilter;
  texture.generateMipmaps = false;
  texture.colorSpace = NoColorSpace;
  texture.needsUpdate = true;
  return texture;
}

const DEFAULT_SKINS = ['alex', 'ari', 'efe', 'kai', 'makena', 'noor', 'steve', 'sunny', 'zuri'];

function uuidHash(uuid: string): number {
  const hex = uuid.replace(/-/g, '');
  if (!/^[0-9a-fA-F]{32}$/.test(hex)) return 0;
  const most = BigInt.asIntN(64, BigInt(`0x${hex.slice(0, 16)}`));
  const least = BigInt.asIntN(64, BigInt(`0x${hex.slice(16)}`));
  const hilo = most ^ least;
  return Number(BigInt.asIntN(32, hilo >> 32n) ^ BigInt.asIntN(32, hilo));
}

export function defaultSkin(uuid: string | null, modernSkins: boolean): { path: string; slim: boolean } {
  const hash = uuid === null ? 0 : uuidHash(uuid);
  if (!modernSkins) {
    const slim = (hash & 1) !== 0;
    return { path: slim ? 'textures/entity/alex.png' : 'textures/entity/steve.png', slim };
  }
  const index = ((hash % 18) + 18) % 18;
  const slim = index < 9;
  const name = DEFAULT_SKINS[index % 9] ?? 'steve';
  return { path: `textures/entity/player/${slim ? 'slim' : 'wide'}/${name}.png`, slim };
}

export class EntityTextures {
  private readonly assets: LoadedAssets;
  private readonly textures = new Map<string, Promise<LoadedTexture | null>>();
  private readonly ready = new Map<string, LoadedTexture | null>();
  private readonly owned: ImageBitmap[] = [];
  private disposed = false;

  constructor(assets: LoadedAssets) {
    this.assets = assets;
  }

  async request(path: string): Promise<LoadedTexture | null> {
    const existing = this.textures.get(path);
    if (existing !== undefined) return existing;
    const promise = this.assets.bitmap(path).then((bitmap) => {
      if (bitmap === null || this.disposed) return null;
      const loaded = { texture: textureFrom(bitmap), width: bitmap.width, height: bitmap.height };
      this.ready.set(path, loaded);
      return loaded;
    });
    this.textures.set(path, promise);
    return promise;
  }

  async first(paths: readonly string[]): Promise<LoadedTexture | null> {
    const available = paths.find((path) => this.assets.pack.file(path) !== null);
    return available === undefined ? null : this.request(available);
  }

  async skin(key: string, image: () => Promise<ImageBitmap | null>, owned: boolean): Promise<LoadedTexture | null> {
    const existing = this.textures.get(key);
    if (existing !== undefined) return existing;
    const promise = image().then((bitmap) => {
      if (bitmap === null) return null;
      if (this.disposed) {
        if (owned) bitmap.close();
        return null;
      }
      const normalized = normalizeSkin(bitmap);
      if (owned && normalized !== bitmap) bitmap.close();
      else if (owned) this.owned.push(bitmap);
      const loaded = { texture: textureFrom(normalized), width: normalized.width, height: normalized.height };
      this.ready.set(key, loaded);
      return loaded;
    }).catch(() => null);
    this.textures.set(key, promise);
    return promise;
  }

  dispose(): void {
    this.disposed = true;
    for (const loaded of this.ready.values()) loaded?.texture.dispose();
    this.ready.clear();
    for (const bitmap of this.owned) bitmap.close();
    this.owned.length = 0;
    this.textures.clear();
  }
}
