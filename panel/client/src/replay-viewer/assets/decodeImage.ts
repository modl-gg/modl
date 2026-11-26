export interface RgbaImage {
  readonly width: number;
  readonly height: number;
  readonly pixels: Uint8ClampedArray;
}

const MAX_DIMENSION = 4096;

export async function decodeBitmap(bytes: Uint8Array<ArrayBuffer>): Promise<ImageBitmap | null> {
  try {
    return await createImageBitmap(new Blob([bytes], { type: 'image/png' }), { premultiplyAlpha: 'none', colorSpaceConversion: 'none' });
  } catch {
    return null;
  }
}

export function bitmapPixels(bitmap: ImageBitmap): RgbaImage | null {
  if (bitmap.width > MAX_DIMENSION || bitmap.height > MAX_DIMENSION || bitmap.width === 0 || bitmap.height === 0) return null;
  const canvas = new OffscreenCanvas(bitmap.width, bitmap.height);
  const context = canvas.getContext('2d', { willReadFrequently: true });
  if (context === null) return null;
  context.drawImage(bitmap, 0, 0);
  return { width: bitmap.width, height: bitmap.height, pixels: context.getImageData(0, 0, bitmap.width, bitmap.height).data };
}

export async function decodeRgba(bytes: Uint8Array<ArrayBuffer>): Promise<RgbaImage | null> {
  const bitmap = await decodeBitmap(bytes);
  if (bitmap === null) return null;
  try {
    return bitmapPixels(bitmap);
  } finally {
    bitmap.close();
  }
}
