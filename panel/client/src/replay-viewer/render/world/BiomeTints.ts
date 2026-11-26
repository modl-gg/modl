import type { Biome, BiomeTable } from './Biomes.ts';
import { swampGrassColor } from './BiomeNoise.ts';
import type { BlockWorld } from './BlockWorld.ts';
import { type Colormaps, DEFAULT_FOLIAGE, DEFAULT_GRASS, type TintKind, sampleColormap } from './Tints.ts';

const BLEND_RADIUS = 2;

export type BlendedKind = Extract<TintKind, 'grass' | 'foliage' | 'dry_foliage' | 'water'>;

const KIND_INDEX: Readonly<Record<BlendedKind, number>> = { grass: 0, foliage: 1, dry_foliage: 2, water: 3 };

export interface TintSource {
  readonly world: BlockWorld;
  readonly biomes: BiomeTable;
  readonly biomeKeys: readonly string[];
  readonly colormaps: Colormaps;
  readonly legacyWater: boolean;
}

export function biomeColor(kind: BlendedKind, biome: Biome, maps: Colormaps, x: number, z: number): number {
  switch (kind) {
    case 'grass': {
      if (biome.grassModifier === 'swamp') return swampGrassColor(x, z);
      const base = biome.grassColor ?? sampleColormap(maps.grass, biome.temperature, biome.downfall, DEFAULT_GRASS);
      return biome.grassModifier === 'dark_forest' ? (((base & 0xfefefe) + 0x28340a) >> 1) & 0xffffff : base;
    }
    case 'foliage':
      return biome.foliageColor ?? sampleColormap(maps.foliage, biome.temperature, biome.downfall, DEFAULT_FOLIAGE);
    case 'dry_foliage':
      return sampleColormap(maps.dryFoliage, biome.temperature, biome.downfall, biome.foliageColor ?? DEFAULT_FOLIAGE);
    case 'water':
      return biome.waterColor;
  }
}

export class BiomeTints {
  private readonly source: TintSource;
  private readonly cache = new Map<number, number>();
  private readonly columns = new Map<number, number>();

  constructor(source: TintSource) {
    this.source = source;
  }

  private columnColor(kind: BlendedKind, x: number, y: number, z: number): number {
    const key = ((((x & 0xfff) * 4096 + (z & 0xfff)) * 128 + ((y >> 2) & 127)) * 4) + KIND_INDEX[kind];
    const cached = this.columns.get(key);
    if (cached !== undefined) return cached;
    const index = this.source.world.biome(x, y, z);
    const biome = this.source.biomes.get(index < 0 ? null : this.source.biomeKeys[index] ?? null);
    const color = biomeColor(kind, biome, this.source.colormaps, x, z);
    this.columns.set(key, color);
    return color;
  }

  color(kind: BlendedKind, x: number, y: number, z: number): number {
    if (kind === 'water' && this.source.legacyWater) return 0xffffff;
    const key = ((((x & 0xfff) * 4096 + (z & 0xfff)) * 128 + ((y >> 2) & 127)) * 4) + KIND_INDEX[kind];
    const cached = this.cache.get(key);
    if (cached !== undefined) return cached;
    let red = 0;
    let green = 0;
    let blue = 0;
    let count = 0;
    for (let dz = -BLEND_RADIUS; dz <= BLEND_RADIUS; dz++) {
      for (let dx = -BLEND_RADIUS; dx <= BLEND_RADIUS; dx++) {
        const color = this.columnColor(kind, x + dx, y, z + dz);
        red += (color >> 16) & 255;
        green += (color >> 8) & 255;
        blue += color & 255;
        count += 1;
      }
    }
    const blended = (Math.floor(red / count) << 16) | (Math.floor(green / count) << 8) | Math.floor(blue / count);
    this.cache.set(key, blended);
    return blended;
  }

  clear(): void {
    this.cache.clear();
    this.columns.clear();
  }
}
