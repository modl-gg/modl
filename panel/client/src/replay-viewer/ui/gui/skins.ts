import { useEffect, useState } from 'react';
import type { AssetLibrary } from '../../assets/AssetLibrary';
import type { GuiPainter } from './painter';

const WIDE_DEFAULTS = ['entity/player/wide/steve', 'entity/steve'];
const SLIM_DEFAULTS = ['entity/player/slim/alex', 'entity/alex', ...WIDE_DEFAULTS];
const LEGACY_SKIN_HEIGHT = 32;

export function defaultSkin(assets: AssetLibrary, slim: boolean): ImageBitmap | null {
  for (const path of slim ? SLIM_DEFAULTS : WIDE_DEFAULTS) {
    const texture = assets.texture(path);
    if (texture !== null) return texture;
  }
  return null;
}

interface LoadedSkin {
  readonly hash: string;
  readonly image: ImageBitmap;
}

export function useSkin(assets: AssetLibrary, hash: string | null, slim: boolean): ImageBitmap | null {
  const [loaded, setLoaded] = useState<LoadedSkin | null>(null);

  useEffect(() => {
    if (hash === null) return undefined;
    let active = true;
    assets.skin(hash).then((image) => {
      if (active) setLoaded({ hash, image });
    }, () => undefined);
    return () => {
      active = false;
    };
  }, [assets, hash]);

  return loaded !== null && loaded.hash === hash ? loaded.image : defaultSkin(assets, slim);
}

export function paintHead(painter: GuiPainter, skin: ImageBitmap | null, x: number, y: number, size: number): void {
  if (skin === null) {
    painter.fill(x, y, size, size, 0xff6b4a2f);
    painter.fill(x + 1, y + Math.floor(size / 2), size - 2, Math.ceil(size / 4), 0xffc8936b);
    return;
  }
  painter.image(skin, 8, 8, 8, 8, x, y, size, size);
  painter.image(skin, 40, 8, 8, 8, x, y, size, size);
}

interface BodyPart {
  readonly base: readonly [number, number];
  readonly overlay: readonly [number, number] | null;
  readonly legacy: readonly [number, number];
  readonly width: number;
  readonly height: number;
  readonly at: readonly [number, number];
}

function bodyParts(slim: boolean): readonly BodyPart[] {
  const armWidth = slim ? 3 : 4;
  return [
    { base: [8, 8], overlay: [40, 8], legacy: [8, 8], width: 8, height: 8, at: [4, 0] },
    { base: [20, 20], overlay: [20, 36], legacy: [20, 20], width: 8, height: 12, at: [4, 8] },
    { base: [44, 20], overlay: [44, 36], legacy: [44, 20], width: armWidth, height: 12, at: [4 - armWidth, 8] },
    { base: [36, 52], overlay: [52, 52], legacy: [44, 20], width: armWidth, height: 12, at: [12, 8] },
    { base: [4, 20], overlay: [4, 36], legacy: [4, 20], width: 4, height: 12, at: [4, 20] },
    { base: [20, 52], overlay: [4, 52], legacy: [4, 20], width: 4, height: 12, at: [8, 20] },
  ];
}

export const BODY_WIDTH = 16;
export const BODY_HEIGHT = 32;

export function paintBodyFront(painter: GuiPainter, skin: ImageBitmap | null, slim: boolean, x: number, y: number, pixel: number): void {
  if (skin === null) return;
  const legacy = skin.height <= LEGACY_SKIN_HEIGHT;
  for (const part of bodyParts(slim)) {
    const targetX = x + part.at[0] * pixel;
    const targetY = y + part.at[1] * pixel;
    const source = legacy ? part.legacy : part.base;
    painter.image(skin, source[0], source[1], part.width, part.height, targetX, targetY, part.width * pixel, part.height * pixel);
    const overlay = legacy ? (part.at[1] === 0 ? part.overlay : null) : part.overlay;
    if (overlay !== null) painter.image(skin, overlay[0], overlay[1], part.width, part.height, targetX, targetY, part.width * pixel, part.height * pixel);
  }
}
