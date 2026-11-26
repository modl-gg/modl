import type { BlockState } from '../../format/BlockState.ts';

export interface Colormap {
  readonly width: number;
  readonly height: number;
  readonly pixels: Uint8ClampedArray;
}

export interface Colormaps {
  readonly grass: Colormap | null;
  readonly foliage: Colormap | null;
  readonly dryFoliage: Colormap | null;
}

export type TintKind = 'none' | 'grass' | 'foliage' | 'dry_foliage' | 'water' | 'constant' | 'redstone' | 'stem';

export interface TintRule {
  readonly kind: TintKind;
  readonly color: number;
}

const WHITE = 0xffffff;
export const LEATHER_COLOR = 0xa06540;
export const DEFAULT_GRASS = 0x91bd59;
export const DEFAULT_FOLIAGE = 0x77ab2f;

const GRASS_TINTED = new Set([
  'grass_block', 'short_grass', 'grass', 'tall_grass', 'fern', 'large_fern', 'potted_fern', 'sugar_cane', 'bush',
]);
const FOLIAGE_TINTED = new Set(['oak_leaves', 'jungle_leaves', 'acacia_leaves', 'dark_oak_leaves', 'vine']);
const DRY_FOLIAGE_TINTED = new Set(['leaf_litter']);
const CONSTANT_TINTS: Readonly<Record<string, number>> = {
  spruce_leaves: 0x619961,
  birch_leaves: 0x80a755,
  mangrove_leaves: 0x92c648,
  lily_pad: 0x208030,
  attached_melon_stem: 0xe0c71c,
  attached_pumpkin_stem: 0xe0c71c,
};
const WATER_TINTED = new Set(['water', 'water_cauldron', 'bubble_column']);

export function tintRule(state: BlockState): TintRule {
  const name = state.name;
  if (GRASS_TINTED.has(name)) return { kind: 'grass', color: DEFAULT_GRASS };
  if (FOLIAGE_TINTED.has(name)) return { kind: 'foliage', color: DEFAULT_FOLIAGE };
  if (DRY_FOLIAGE_TINTED.has(name)) return { kind: 'dry_foliage', color: DEFAULT_FOLIAGE };
  const constant = CONSTANT_TINTS[name];
  if (constant !== undefined) return { kind: 'constant', color: constant };
  if (WATER_TINTED.has(name)) return { kind: 'water', color: 0x3f76e4 };
  if (name === 'redstone_wire') return { kind: 'redstone', color: redstoneColor(Number.parseInt(state.properties['power'] ?? '0', 10)) };
  if (name === 'melon_stem' || name === 'pumpkin_stem') return { kind: 'stem', color: stemColor(Number.parseInt(state.properties['age'] ?? '0', 10)) };
  return { kind: 'none', color: WHITE };
}

function clamp01(value: number): number {
  return Math.min(1, Math.max(0, value));
}

export function redstoneColor(power: number): number {
  const level = Number.isFinite(power) ? Math.min(15, Math.max(0, power)) : 0;
  const f = level / 15;
  const red = f * 0.6 + (f > 0 ? 0.4 : 0.3);
  const green = clamp01(f * f * 0.7 - 0.5);
  const blue = clamp01(f * f * 0.6 - 0.7);
  return (Math.round(red * 255) << 16) | (Math.round(green * 255) << 8) | Math.round(blue * 255);
}

export function stemColor(age: number): number {
  const level = Number.isFinite(age) ? Math.min(7, Math.max(0, age)) : 0;
  return ((level * 32) << 16) | ((255 - level * 8) << 8) | (level * 4);
}

export function sampleColormap(map: Colormap | null, temperature: number, downfall: number, fallback: number): number {
  if (map === null) return fallback;
  const t = clamp01(temperature);
  const d = clamp01(downfall) * t;
  const x = Math.floor((1 - t) * (map.width - 1));
  const y = Math.floor((1 - d) * (map.height - 1));
  const index = (y * map.width + x) * 4;
  const red = map.pixels[index] ?? 0;
  const green = map.pixels[index + 1] ?? 0;
  const blue = map.pixels[index + 2] ?? 0;
  return (red << 16) | (green << 8) | blue;
}
