import { AssetLoadError } from './AssetLibrary.ts';
import { decodeBitmap } from './decodeImage.ts';
import { readStream } from '../format/readStream.ts';

const SKIN_HOST = 'https://textures.minecraft.net/texture/';
const SKIN_HASH = /^[0-9a-f]{16,64}$/;
const MAX_SKIN_BYTES = 256 * 1024;

export function skinUrl(hash: string): string | null {
  const normalized = hash.trim().toLowerCase();
  return SKIN_HASH.test(normalized) ? SKIN_HOST + normalized : null;
}

function copyRegion(context: OffscreenCanvasRenderingContext2D, source: OffscreenCanvas, x: number, y: number, width: number, height: number, targetX: number, targetY: number): void {
  context.save();
  context.translate(targetX + width, targetY);
  context.scale(-1, 1);
  context.drawImage(source, x, y, width, height, 0, 0, width, height);
  context.restore();
}

const LEGACY_LIMB_COPIES: readonly (readonly [number, number, number, number, number, number])[] = [
  [4, 16, 4, 4, 20, 48],
  [8, 16, 4, 4, 24, 48],
  [0, 20, 4, 12, 24, 52],
  [4, 20, 4, 12, 20, 52],
  [8, 20, 4, 12, 16, 52],
  [12, 20, 4, 12, 28, 52],
  [44, 16, 4, 4, 36, 48],
  [48, 16, 4, 4, 40, 48],
  [40, 20, 4, 12, 40, 52],
  [44, 20, 4, 12, 36, 52],
  [48, 20, 4, 12, 32, 52],
  [52, 20, 4, 12, 44, 52],
];

export function normalizeSkin(bitmap: ImageBitmap): ImageBitmap | OffscreenCanvas {
  if (bitmap.width !== 64 || bitmap.height !== 32) return bitmap;
  const source = new OffscreenCanvas(64, 32);
  source.getContext('2d')?.drawImage(bitmap, 0, 0);
  const canvas = new OffscreenCanvas(64, 64);
  const context = canvas.getContext('2d');
  if (context === null) return bitmap;
  context.imageSmoothingEnabled = false;
  context.drawImage(source, 0, 0);
  for (const [x, y, width, height, targetX, targetY] of LEGACY_LIMB_COPIES) copyRegion(context, source, x, y, width, height, targetX, targetY);
  return canvas;
}

export async function fetchSkin(hash: string, signal: AbortSignal): Promise<ImageBitmap> {
  const url = skinUrl(hash);
  if (url === null) throw new AssetLoadError('corrupt', 'invalid skin hash');
  let response: Response;
  try {
    response = await fetch(url, { signal, credentials: 'omit', referrerPolicy: 'no-referrer', redirect: 'error' });
  } catch {
    throw new AssetLoadError(signal.aborted ? 'aborted' : 'network', 'skin download failed');
  }
  if (!response.ok || response.body === null) throw new AssetLoadError('network', `skin download answered HTTP ${String(response.status)}`);
  const bytes = await readStream(response.body, MAX_SKIN_BYTES, () => new AssetLoadError('corrupt', 'skin too large'));
  const bitmap = await decodeSkin(bytes);
  if (bitmap === null) throw new AssetLoadError('corrupt', 'skin is not a 64x64 or 64x32 PNG');
  return bitmap;
}

function declaredSkinSize(bytes: Uint8Array): boolean {
  if (bytes.byteLength < 24) return false;
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  if (view.getUint32(0) !== 0x89504e47 || view.getUint32(12) !== 0x49484452) return false;
  const width = view.getUint32(16);
  const height = view.getUint32(20);
  return width === 64 && (height === 64 || height === 32);
}

export async function decodeSkin(bytes: Uint8Array<ArrayBuffer>): Promise<ImageBitmap | null> {
  if (bytes.byteLength > MAX_SKIN_BYTES || !declaredSkinSize(bytes)) return null;
  const bitmap = await decodeBitmap(bytes);
  if (bitmap === null) return null;
  if (bitmap.width !== 64 || (bitmap.height !== 64 && bitmap.height !== 32)) {
    bitmap.close();
    return null;
  }
  return bitmap;
}
