import { type JsonRecord, arrayField, booleanField, isRecord, numberField, recordField } from '../../assets/json.ts';

export type AlphaClass = 'solid' | 'cutout' | 'translucent';

export interface SpriteImage {
  readonly id: string;
  readonly width: number;
  readonly height: number;
  readonly pixels: Uint8ClampedArray;
  readonly metadata: unknown;
}

export interface AnimationFrame {
  readonly index: number;
  readonly ticks: number;
}

export interface SpriteAnimation {
  readonly frames: readonly AnimationFrame[];
  readonly interpolate: boolean;
  readonly totalTicks: number;
}

export interface AtlasSprite {
  readonly id: string;
  readonly x: number;
  readonly y: number;
  readonly width: number;
  readonly height: number;
  readonly frameOrigins: readonly (readonly [number, number])[];
  readonly animation: SpriteAnimation | null;
  readonly alpha: AlphaClass;
  readonly animationSlot: number;
}

export interface AtlasLayout {
  readonly size: number;
  readonly sprites: ReadonlyMap<string, AtlasSprite>;
}

export interface AtlasImage extends AtlasLayout {
  readonly levels: readonly Uint8Array<ArrayBuffer>[];
  readonly animated: readonly AtlasSprite[];
}

const CELL = 16;
const MAX_ATLAS = 8192;

function frameSize(sprite: SpriteImage): { width: number; height: number } {
  const animation = isRecord(sprite.metadata) ? recordField(sprite.metadata, 'animation') : null;
  if (animation === null) return { width: sprite.width, height: sprite.height };
  const declaredWidth = numberField(animation, 'width');
  const declaredHeight = numberField(animation, 'height');
  const width = declaredWidth ?? Math.min(sprite.width, sprite.height);
  const height = declaredHeight ?? width;
  return { width: Math.min(width, sprite.width), height: Math.min(height, sprite.height) };
}

function parseAnimation(animation: JsonRecord | null, frameCount: number): SpriteAnimation | null {
  if (animation === null || frameCount <= 1) return null;
  const defaultTicks = Math.max(1, numberField(animation, 'frametime') ?? 1);
  const listed = arrayField(animation, 'frames');
  const frames: AnimationFrame[] = [];
  for (const entry of listed) {
    if (typeof entry === 'number' && entry >= 0 && entry < frameCount) frames.push({ index: entry, ticks: defaultTicks });
    else if (isRecord(entry)) {
      const index = numberField(entry, 'index');
      if (index !== null && index >= 0 && index < frameCount) frames.push({ index, ticks: Math.max(1, numberField(entry, 'time') ?? defaultTicks) });
    }
  }
  if (frames.length === 0) {
    for (let index = 0; index < frameCount; index++) frames.push({ index, ticks: defaultTicks });
  }
  return {
    frames,
    interpolate: booleanField(animation, 'interpolate') ?? false,
    totalTicks: frames.reduce((sum, frame) => sum + frame.ticks, 0),
  };
}

function classifyAlpha(pixels: Uint8ClampedArray, width: number, frameWidth: number, frameHeight: number): AlphaClass {
  let transparent = false;
  for (let y = 0; y < frameHeight; y++) {
    for (let x = 0; x < frameWidth; x++) {
      const alpha = pixels[(y * width + x) * 4 + 3] ?? 255;
      if (alpha === 255) continue;
      if (alpha === 0) transparent = true;
      else return 'translucent';
    }
  }
  return transparent ? 'cutout' : 'solid';
}

function align(value: number): number {
  return Math.ceil(value / CELL) * CELL;
}

interface Placement {
  readonly sprite: SpriteImage;
  readonly frameWidth: number;
  readonly frameHeight: number;
  readonly frameCount: number;
  readonly origins: [number, number][];
}

function pack(placements: Placement[]): number {
  const slots = placements.flatMap((placement) => placement.origins.map((_, frame) => ({ placement, frame })));
  slots.sort((left, right) => align(right.placement.frameHeight) - align(left.placement.frameHeight) || align(right.placement.frameWidth) - align(left.placement.frameWidth));
  const area = slots.reduce((sum, slot) => sum + align(slot.placement.frameWidth) * align(slot.placement.frameHeight), 0);
  let size = CELL * 16;
  while (size * size < area && size < MAX_ATLAS) size *= 2;
  for (; size <= MAX_ATLAS; size *= 2) {
    let x = 0;
    let y = 0;
    let shelf = 0;
    let fits = true;
    for (const slot of slots) {
      const width = align(slot.placement.frameWidth);
      const height = align(slot.placement.frameHeight);
      if (x + width > size) {
        x = 0;
        y += shelf;
        shelf = 0;
      }
      if (y + height > size || width > size) {
        fits = false;
        break;
      }
      slot.placement.origins[slot.frame] = [x, y];
      x += width;
      shelf = Math.max(shelf, height);
    }
    if (fits) return size;
  }
  throw new Error('texture atlas exceeds the maximum size');
}

const TO_LINEAR = Float64Array.from({ length: 256 }, (_, value) => Math.pow(value / 255, 2.2));

function fromLinear(value: number): number {
  return Math.round(Math.pow(value, 1 / 2.2) * 255);
}

function downsample(source: Uint8Array, size: number): Uint8Array<ArrayBuffer> {
  const half = size / 2;
  const target = new Uint8Array(half * half * 4);
  for (let y = 0; y < half; y++) {
    for (let x = 0; x < half; x++) {
      const a = ((y * 2) * size + x * 2) * 4;
      const b = a + 4;
      const c = a + size * 4;
      const d = c + 4;
      const alphaA = source[a + 3] ?? 0;
      const alphaB = source[b + 3] ?? 0;
      const alphaC = source[c + 3] ?? 0;
      const alphaD = source[d + 3] ?? 0;
      const weight = alphaA + alphaB + alphaC + alphaD;
      const out = (y * half + x) * 4;
      for (let channel = 0; channel < 3; channel++) {
        const la = TO_LINEAR[source[a + channel] ?? 0] ?? 0;
        const lb = TO_LINEAR[source[b + channel] ?? 0] ?? 0;
        const lc = TO_LINEAR[source[c + channel] ?? 0] ?? 0;
        const ld = TO_LINEAR[source[d + channel] ?? 0] ?? 0;
        target[out + channel] = fromLinear(weight === 0 ? (la + lb + lc + ld) / 4 : (la * alphaA + lb * alphaB + lc * alphaC + ld * alphaD) / weight);
      }
      target[out + 3] = Math.round(weight / 4);
    }
  }
  return target;
}

export function buildAtlas(images: readonly SpriteImage[]): AtlasImage {
  const placements: Placement[] = images.map((sprite) => {
    const { width, height } = frameSize(sprite);
    const columns = Math.max(1, Math.floor(sprite.width / width));
    const rows = Math.max(1, Math.floor(sprite.height / height));
    const frameCount = columns * rows;
    return { sprite, frameWidth: width, frameHeight: height, frameCount, origins: Array.from({ length: frameCount }, (): [number, number] => [0, 0]) };
  });
  const size = pack(placements);
  const base = new Uint8Array(size * size * 4);
  const sprites = new Map<string, AtlasSprite>();
  const animated: AtlasSprite[] = [];
  for (const placement of placements) {
    const { sprite, frameWidth, frameHeight, origins } = placement;
    const columns = Math.max(1, Math.floor(sprite.width / frameWidth));
    origins.forEach((origin, frame) => {
      const sourceX = (frame % columns) * frameWidth;
      const sourceY = Math.floor(frame / columns) * frameHeight;
      for (let row = 0; row < frameHeight; row++) {
        const sourceOffset = ((sourceY + row) * sprite.width + sourceX) * 4;
        const targetOffset = ((origin[1] + row) * size + origin[0]) * 4;
        base.set(sprite.pixels.subarray(sourceOffset, sourceOffset + frameWidth * 4), targetOffset);
      }
    });
    const animation = parseAnimation(isRecord(sprite.metadata) ? recordField(sprite.metadata, 'animation') : null, placement.frameCount);
    const first = origins[0] ?? [0, 0];
    const entry: AtlasSprite = {
      id: sprite.id,
      x: first[0],
      y: first[1],
      width: frameWidth,
      height: frameHeight,
      frameOrigins: origins,
      animation,
      alpha: classifyAlpha(sprite.pixels, sprite.width, frameWidth, frameHeight),
      animationSlot: animation === null ? 0 : animated.length + 1,
    };
    if (animation !== null) animated.push(entry);
    sprites.set(sprite.id, entry);
  }
  const levels: Uint8Array<ArrayBuffer>[] = [base];
  let current = base;
  for (let level = 1; size >> level >= 1; level++) {
    current = downsample(current, size >> (level - 1));
    levels.push(current);
  }
  return { size, levels, sprites, animated };
}

export function frameAt(animation: SpriteAnimation, tick: number): { frame: number; next: number; blend: number } {
  let remaining = ((tick % animation.totalTicks) + animation.totalTicks) % animation.totalTicks;
  for (let position = 0; position < animation.frames.length; position++) {
    const frame = animation.frames[position];
    if (frame === undefined) break;
    if (remaining < frame.ticks) {
      const following = animation.frames[(position + 1) % animation.frames.length] ?? frame;
      return { frame: frame.index, next: following.index, blend: animation.interpolate ? remaining / frame.ticks : 0 };
    }
    remaining -= frame.ticks;
  }
  const first = animation.frames[0]?.index ?? 0;
  return { frame: first, next: first, blend: 0 };
}
