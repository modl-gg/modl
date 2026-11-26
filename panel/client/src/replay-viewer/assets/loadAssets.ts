import { BlockLibrary } from '../render/world/BlockLibrary.ts';
import { blockSource } from '../render/world/WorldService.ts';
import {
  type AssetLibrary,
  AssetLoadError,
  type AssetProgressListener,
  type BitmapFont,
  type GuiSprite,
} from './AssetLibrary.ts';
import { BitmapFontImpl, type FontSheet, fontProviders, legacyAsciiProvider } from './BitmapFontImpl.ts';
import { decodeBitmap } from './decodeImage.ts';
import { legacySprite, modernSpritePath, nineSliceOf } from './GuiSprites.ts';
import { ItemIcons } from './ItemIcons.ts';
import type { MinecraftVersion } from '../format/MinecraftVersion.ts';
import { unpackFiles } from './jar/FileBundle.ts';
import { parseLoaderMessage } from './jar/workerProtocol.ts';
import { isRecord } from './json.ts';
import { type Files, ResourcePack } from './ResourcePack.ts';
import { fetchSkin } from './Skins.ts';
import { SPECIAL_ITEM_TEXTURES } from './SpecialItemModels.ts';

const DECODED_PREFIXES = [
  'textures/gui/',
  'textures/mob_effect/',
  'textures/item/',
  'textures/items/',
  'textures/block/',
  'textures/blocks/',
  'textures/entity/player/',
  'textures/font/',
];
const DECODED_FILES = ['textures/entity/steve.png', 'textures/entity/alex.png', ...SPECIAL_ITEM_TEXTURES.map((id) => `textures/${id}.png`)];
const DECODE_BATCH = 64;

export interface LoadedAssets extends AssetLibrary {
  readonly pack: ResourcePack;
  readonly library: BlockLibrary;
  readonly files: { readonly primary: Files; readonly models: Files | null };
  bitmap(path: string): Promise<ImageBitmap | null>;
}

function shouldDecode(path: string): boolean {
  return path.endsWith('.png') && (DECODED_PREFIXES.some((prefix) => path.startsWith(prefix)) || DECODED_FILES.includes(path));
}

function parseLang(pack: ResourcePack): Map<string, string> {
  const lang = new Map<string, string>();
  const json = pack.json('lang/en_us.json');
  if (isRecord(json)) {
    for (const [key, value] of Object.entries(json)) {
      if (typeof value === 'string') lang.set(key, value);
    }
    return lang;
  }
  const legacy = pack.file('lang/en_US.lang');
  if (legacy === null) return lang;
  for (const line of new TextDecoder().decode(legacy).split('\n')) {
    const equals = line.indexOf('=');
    if (equals > 0 && !line.startsWith('#')) lang.set(line.slice(0, equals), line.slice(equals + 1).replace(/\r$/, ''));
  }
  return lang;
}

class AssetLibraryImpl implements LoadedAssets {
  readonly version: string;
  readonly pack: ResourcePack;
  readonly library: BlockLibrary;
  readonly files: { readonly primary: Files; readonly models: Files | null };
  private readonly bitmaps: Map<string, ImageBitmap>;
  private readonly pending = new Map<string, Promise<ImageBitmap | null>>();
  private readonly translations: Map<string, string>;
  private readonly sprites = new Map<string, GuiSprite | null>();
  private readonly skins = new Map<string, Promise<ImageBitmap>>();
  private readonly icons: ItemIcons;
  private readonly bitmapFont: BitmapFont;
  private readonly controller = new AbortController();
  private disposed = false;

  constructor(version: string, files: { primary: Files; models: Files | null }, bitmaps: Map<string, ImageBitmap>) {
    this.version = version;
    this.files = files;
    this.pack = new ResourcePack({ version, primary: files.primary, models: files.models });
    this.bitmaps = bitmaps;
    this.translations = parseLang(this.pack);
    this.library = new BlockLibrary(blockSource(this.pack));
    this.icons = new ItemIcons(this.pack, this.library, (id) => this.textureById(id));
    this.bitmapFont = this.buildFont();
  }

  private textureById(id: string): ImageBitmap | null {
    const path = this.pack.texturePath(id);
    return path === null ? null : this.bitmaps.get(path) ?? null;
  }

  private buildFont(): BitmapFont {
    const providers = this.pack.legacy || this.pack.json('font/default.json') === null
      ? { bitmaps: [legacyAsciiProvider()], spaces: new Map<string, number>() }
      : fontProviders((path) => this.pack.json(path));
    const sheets = new Map<string, FontSheet>();
    for (const provider of providers.bitmaps) {
      const bitmap = this.bitmaps.get(`textures/${provider.file}`);
      if (bitmap === undefined || sheets.has(provider.file)) continue;
      const canvas = new OffscreenCanvas(bitmap.width, bitmap.height);
      const context = canvas.getContext('2d', { willReadFrequently: true });
      if (context === null) continue;
      context.drawImage(bitmap, 0, 0);
      sheets.set(provider.file, { id: provider.file, image: bitmap, pixels: context.getImageData(0, 0, bitmap.width, bitmap.height).data });
    }
    return new BitmapFontImpl(providers.bitmaps, sheets, providers.spaces);
  }

  texture(path: string): ImageBitmap | null {
    const resolved = this.pack.texturePath(path);
    return this.bitmaps.get(resolved ?? `textures/${path}.png`) ?? null;
  }

  async bitmap(path: string): Promise<ImageBitmap | null> {
    const cached = this.bitmaps.get(path);
    if (cached !== undefined) return cached;
    const pending = this.pending.get(path);
    if (pending !== undefined) return pending;
    const bytes = this.pack.file(path);
    if (bytes === null) return Promise.resolve(null);
    const promise = decodeBitmap(bytes).then((bitmap) => {
      if (bitmap === null) return null;
      if (this.disposed) {
        bitmap.close();
        return null;
      }
      this.bitmaps.set(path, bitmap);
      return bitmap;
    });
    this.pending.set(path, promise);
    return promise;
  }

  guiSprite(id: string): GuiSprite | null {
    if (this.sprites.has(id)) return this.sprites.get(id) ?? null;
    const sprite = this.buildSprite(id);
    this.sprites.set(id, sprite);
    return sprite;
  }

  private buildSprite(id: string): GuiSprite | null {
    const modernPath = modernSpritePath(id);
    const modern = this.bitmaps.get(modernPath);
    if (modern !== undefined) {
      return { image: modern, width: modern.width, height: modern.height, nineSlice: nineSliceOf(this.pack.json(`${modernPath}.mcmeta`)) };
    }
    const region = legacySprite(id);
    if (region === null) return null;
    const sheet = this.bitmaps.get(`textures/${region.texture}.png`);
    if (sheet === undefined) return null;
    const scale = sheet.width / 256;
    const canvas = new OffscreenCanvas(region.width, region.height);
    const context = canvas.getContext('2d');
    if (context === null) return null;
    context.imageSmoothingEnabled = false;
    context.drawImage(sheet, region.x * scale, region.y * scale, region.width * scale, region.height * scale, 0, 0, region.width, region.height);
    const image = canvas.transferToImageBitmap();
    return { image, width: region.width, height: region.height, nineSlice: region.nineSlice };
  }

  font(): BitmapFont {
    return this.bitmapFont;
  }

  lang(key: string): string | undefined {
    return this.translations.get(key);
  }

  async skin(textureHash: string): Promise<ImageBitmap> {
    const key = textureHash.toLowerCase();
    const cached = this.skins.get(key);
    if (cached !== undefined) return cached;
    const promise = fetchSkin(key, this.controller.signal);
    promise.catch(() => {
      this.skins.delete(key);
    });
    this.skins.set(key, promise);
    return promise;
  }

  itemIcon(itemKey: string): ImageBitmap | null {
    return this.icons.icon(itemKey);
  }

  dispose(): void {
    this.disposed = true;
    this.controller.abort();
    for (const bitmap of this.bitmaps.values()) bitmap.close();
    this.bitmaps.clear();
    for (const sprite of this.sprites.values()) sprite?.image.close();
    this.sprites.clear();
    for (const skin of this.skins.values()) void skin.then((bitmap) => { bitmap.close(); }, () => undefined);
    this.skins.clear();
    this.icons.dispose();
  }
}

function startWorker(): Worker {
  return new Worker(new URL('./jar/assetLoader.worker.ts', import.meta.url), { type: 'module', name: 'modl-replay-assets' });
}

async function loadBundles(version: string, onProgress: AssetProgressListener, signal: AbortSignal): Promise<{ primary: Files; models: Files | null }> {
  return new Promise((resolve, reject) => {
    const worker = startWorker();
    const abort = (): void => {
      worker.terminate();
      reject(new AssetLoadError('aborted', 'aborted'));
    };
    signal.addEventListener('abort', abort, { once: true });
    const finish = (): void => {
      signal.removeEventListener('abort', abort);
      worker.terminate();
    };
    worker.addEventListener('error', () => {
      finish();
      reject(new AssetLoadError('corrupt', 'asset worker failed'));
    });
    worker.addEventListener('message', (event: MessageEvent<unknown>) => {
      const message = parseLoaderMessage(event.data);
      if (message === null) return;
      switch (message.type) {
        case 'progress':
          onProgress(message.loaded, message.total);
          return;
        case 'failure':
          finish();
          reject(new AssetLoadError(message.kind, message.message));
          return;
        case 'done': {
          finish();
          let primary: Files | null = null;
          let models: Files | null = null;
          for (const bundle of message.bundles) {
            const files = unpackFiles(bundle.bytes);
            if (files === null) {
              reject(new AssetLoadError('corrupt', 'cached assets are unreadable'));
              return;
            }
            if (bundle.role === 'primary') primary = files;
            else models = files;
          }
          if (primary === null) {
            reject(new AssetLoadError('corrupt', 'missing primary assets'));
            return;
          }
          resolve({ primary, models });
          return;
        }
      }
    });
    if (signal.aborted) {
      abort();
      return;
    }
    worker.postMessage({ version });
  });
}

async function decodeAll(files: Files, signal: AbortSignal): Promise<Map<string, ImageBitmap>> {
  const paths = [...files.keys()].filter(shouldDecode);
  const bitmaps = new Map<string, ImageBitmap>();
  for (let start = 0; start < paths.length; start += DECODE_BATCH) {
    if (signal.aborted) {
      for (const bitmap of bitmaps.values()) bitmap.close();
      throw new AssetLoadError('aborted', 'aborted');
    }
    const batch = paths.slice(start, start + DECODE_BATCH);
    const decoded = await Promise.all(batch.map(async (path): Promise<[string, ImageBitmap | null]> => {
      const bytes = files.get(path);
      return [path, bytes === undefined ? null : await decodeBitmap(bytes)];
    }));
    for (const [path, bitmap] of decoded) {
      if (bitmap !== null) bitmaps.set(path, bitmap);
    }
  }
  return bitmaps;
}

export async function loadAssets(version: MinecraftVersion, onProgress: AssetProgressListener, signal: AbortSignal): Promise<LoadedAssets> {
  const files = await loadBundles(version.id, onProgress, signal);
  const bitmaps = await decodeAll(files.primary, signal);
  return new AssetLibraryImpl(version.id, files, bitmaps);
}
