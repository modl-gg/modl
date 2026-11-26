import { bareName } from '../../format/ids.ts';
import { type JsonRecord, isRecord, numberField, recordField, stringField } from '../../assets/json.ts';

export type GrassModifier = 'none' | 'swamp' | 'dark_forest';

export interface Biome {
  readonly temperature: number;
  readonly downfall: number;
  readonly grassColor: number | null;
  readonly foliageColor: number | null;
  readonly waterColor: number;
  readonly grassModifier: GrassModifier;
  readonly skyColor: number | null;
  readonly fogColor: number | null;
}

type BiomeRow = readonly [number, number, number | null, number | null, number, GrassModifier, number | null];

const KNOWN_BIOMES: Readonly<Record<string, BiomeRow>> = {
  badlands: [2.0, 0.0, 0x90814d, 0x9e814d, 0x3f76e4, 'none', 0x6eb1ff],
  bamboo_jungle: [0.95, 0.9, null, null, 0x3f76e4, 'none', 0x77a8ff],
  basalt_deltas: [2.0, 0.0, null, null, 0x3f76e4, 'none', null],
  beach: [0.8, 0.4, null, null, 0x3f76e4, 'none', 0x78a7ff],
  birch_forest: [0.6, 0.6, null, null, 0x3f76e4, 'none', 0x7aa5ff],
  cherry_grove: [0.5, 0.8, 0xb6db61, 0xb6db61, 0x5db7ef, 'none', 0x7ba4ff],
  cold_ocean: [0.5, 0.5, null, null, 0x3d57d6, 'none', 0x7ba4ff],
  crimson_forest: [2.0, 0.0, null, null, 0x3f76e4, 'none', null],
  dark_forest: [0.7, 0.8, null, null, 0x3f76e4, 'dark_forest', 0x79a6ff],
  deep_cold_ocean: [0.5, 0.5, null, null, 0x3d57d6, 'none', 0x7ba4ff],
  deep_dark: [0.8, 0.4, null, null, 0x3f76e4, 'none', 0x78a7ff],
  deep_frozen_ocean: [0.5, 0.5, null, null, 0x3938c9, 'none', 0x7ba4ff],
  deep_lukewarm_ocean: [0.5, 0.5, null, null, 0x45adf2, 'none', 0x7ba4ff],
  deep_ocean: [0.5, 0.5, null, null, 0x3f76e4, 'none', 0x7ba4ff],
  desert: [2.0, 0.0, null, null, 0x3f76e4, 'none', 0x6eb1ff],
  dripstone_caves: [0.8, 0.4, null, null, 0x3f76e4, 'none', 0x78a7ff],
  end_barrens: [0.5, 0.5, null, null, 0x3f76e4, 'none', null],
  end_highlands: [0.5, 0.5, null, null, 0x3f76e4, 'none', null],
  end_midlands: [0.5, 0.5, null, null, 0x3f76e4, 'none', null],
  eroded_badlands: [2.0, 0.0, 0x90814d, 0x9e814d, 0x3f76e4, 'none', 0x6eb1ff],
  flower_forest: [0.7, 0.8, null, null, 0x3f76e4, 'none', 0x79a6ff],
  forest: [0.7, 0.8, null, null, 0x3f76e4, 'none', 0x79a6ff],
  frozen_ocean: [0.0, 0.5, null, null, 0x3938c9, 'none', 0x7fa1ff],
  frozen_peaks: [-0.7, 0.9, null, null, 0x3f76e4, 'none', 0x859dff],
  frozen_river: [0.0, 0.5, null, null, 0x3938c9, 'none', 0x7fa1ff],
  grove: [-0.2, 0.8, null, null, 0x3f76e4, 'none', 0x81a0ff],
  ice_spikes: [0.0, 0.5, null, null, 0x3f76e4, 'none', 0x7fa1ff],
  jagged_peaks: [-0.7, 0.9, null, null, 0x3f76e4, 'none', 0x859dff],
  jungle: [0.95, 0.9, null, null, 0x3f76e4, 'none', 0x77a8ff],
  lukewarm_ocean: [0.5, 0.5, null, null, 0x45adf2, 'none', 0x7ba4ff],
  lush_caves: [0.5, 0.5, null, null, 0x3f76e4, 'none', 0x7ba4ff],
  mangrove_swamp: [0.8, 0.9, null, 0x8db127, 0x3a7a6a, 'swamp', 0x78a7ff],
  meadow: [0.5, 0.8, null, null, 0x0e4ecf, 'none', 0x7ba4ff],
  mushroom_fields: [0.9, 1.0, null, null, 0x3f76e4, 'none', 0x77a8ff],
  nether_wastes: [2.0, 0.0, null, null, 0x3f76e4, 'none', null],
  ocean: [0.5, 0.5, null, null, 0x3f76e4, 'none', 0x7ba4ff],
  old_growth_birch_forest: [0.6, 0.6, null, null, 0x3f76e4, 'none', 0x7aa5ff],
  old_growth_pine_taiga: [0.3, 0.8, null, null, 0x3f76e4, 'none', 0x7ca3ff],
  old_growth_spruce_taiga: [0.25, 0.8, null, null, 0x3f76e4, 'none', 0x7da3ff],
  pale_garden: [0.7, 0.8, 0x778272, 0x878d76, 0x76889d, 'none', 0xb9b9b9],
  plains: [0.8, 0.4, null, null, 0x3f76e4, 'none', 0x78a7ff],
  river: [0.5, 0.5, null, null, 0x3f76e4, 'none', 0x7ba4ff],
  savanna: [2.0, 0.0, null, null, 0x3f76e4, 'none', 0x6eb1ff],
  savanna_plateau: [2.0, 0.0, null, null, 0x3f76e4, 'none', 0x6eb1ff],
  small_end_islands: [0.5, 0.5, null, null, 0x3f76e4, 'none', null],
  snowy_beach: [0.05, 0.3, null, null, 0x3d57d6, 'none', 0x7fa1ff],
  snowy_plains: [0.0, 0.5, null, null, 0x3f76e4, 'none', 0x7fa1ff],
  snowy_slopes: [-0.3, 0.9, null, null, 0x3f76e4, 'none', 0x829fff],
  snowy_taiga: [-0.5, 0.4, null, null, 0x3d57d6, 'none', 0x839eff],
  soul_sand_valley: [2.0, 0.0, null, null, 0x3f76e4, 'none', null],
  sparse_jungle: [0.95, 0.8, null, null, 0x3f76e4, 'none', 0x77a8ff],
  stony_peaks: [1.0, 0.3, null, null, 0x3f76e4, 'none', 0x76a8ff],
  stony_shore: [0.2, 0.3, null, null, 0x3f76e4, 'none', 0x7da2ff],
  sunflower_plains: [0.8, 0.4, null, null, 0x3f76e4, 'none', 0x78a7ff],
  swamp: [0.8, 0.9, null, 0x6a7039, 0x617b64, 'swamp', 0x78a7ff],
  taiga: [0.25, 0.8, null, null, 0x3f76e4, 'none', 0x7da3ff],
  the_end: [0.5, 0.5, null, null, 0x3f76e4, 'none', null],
  the_void: [0.5, 0.5, null, null, 0x3f76e4, 'none', 0x7ba4ff],
  warm_ocean: [0.5, 0.5, null, null, 0x43d5ee, 'none', 0x7ba4ff],
  warped_forest: [2.0, 0.0, null, null, 0x3f76e4, 'none', null],
  windswept_forest: [0.2, 0.3, null, null, 0x3f76e4, 'none', 0x7da2ff],
  windswept_gravelly_hills: [0.2, 0.3, null, null, 0x3f76e4, 'none', 0x7da2ff],
  windswept_hills: [0.2, 0.3, null, null, 0x3f76e4, 'none', 0x7da2ff],
  windswept_savanna: [2.0, 0.0, null, null, 0x3f76e4, 'none', 0x6eb1ff],
  wooded_badlands: [2.0, 0.0, 0x90814d, 0x9e814d, 0x3f76e4, 'none', 0x6eb1ff],
};

const LEGACY_BIOME_KEYS: Readonly<Record<string, string>> = {
  badlands_plateau: 'badlands',
  bamboo_jungle_hills: 'bamboo_jungle',
  birch_forest_hills: 'birch_forest',
  dark_forest_hills: 'dark_forest',
  deep_warm_ocean: 'warm_ocean',
  desert_hills: 'desert',
  desert_lakes: 'desert',
  giant_spruce_taiga: 'old_growth_spruce_taiga',
  giant_spruce_taiga_hills: 'old_growth_spruce_taiga',
  giant_tree_taiga: 'old_growth_pine_taiga',
  giant_tree_taiga_hills: 'old_growth_pine_taiga',
  gravelly_mountains: 'windswept_gravelly_hills',
  jungle_edge: 'sparse_jungle',
  jungle_hills: 'jungle',
  modified_badlands_plateau: 'badlands',
  modified_gravelly_mountains: 'windswept_gravelly_hills',
  modified_jungle: 'jungle',
  modified_jungle_edge: 'sparse_jungle',
  modified_wooded_badlands_plateau: 'wooded_badlands',
  mountain_edge: 'windswept_hills',
  mountains: 'windswept_hills',
  mushroom_field_shore: 'mushroom_fields',
  nether: 'nether_wastes',
  savanna_plateau: 'savanna_plateau',
  shattered_savanna: 'windswept_savanna',
  shattered_savanna_plateau: 'windswept_savanna',
  snowy_mountains: 'snowy_plains',
  snowy_taiga_hills: 'snowy_taiga',
  snowy_taiga_mountains: 'snowy_taiga',
  snowy_tundra: 'snowy_plains',
  stone_shore: 'stony_shore',
  swamp_hills: 'swamp',
  taiga_hills: 'taiga',
  taiga_mountains: 'taiga',
  tall_birch_forest: 'old_growth_birch_forest',
  tall_birch_hills: 'old_growth_birch_forest',
  wooded_badlands_plateau: 'wooded_badlands',
  wooded_hills: 'forest',
  wooded_mountains: 'windswept_forest',
};

const DEFAULT_KEY = 'plains';

export const DEFAULT_SKY_COLOR = 0x78a7ff;

const NETHER_FOG: Readonly<Record<string, number>> = {
  nether_wastes: 0x330808,
  crimson_forest: 0x330303,
  warped_forest: 0x1a051a,
  soul_sand_valley: 0x1b4745,
  basalt_deltas: 0x685f70,
};

function rowBiome(key: string, row: BiomeRow): Biome {
  return {
    temperature: row[0],
    downfall: row[1],
    grassColor: row[2],
    foliageColor: row[3],
    waterColor: row[4],
    grassModifier: row[5],
    skyColor: row[6],
    fogColor: NETHER_FOG[key] ?? null,
  };
}

function colorValue(value: unknown): number | null {
  if (typeof value === 'number' && Number.isInteger(value)) return value & 0xffffff;
  if (typeof value === 'string' && /^#[0-9a-fA-F]{6}$/.test(value)) return Number.parseInt(value.slice(1), 16);
  return null;
}

function grassModifier(value: string | null): GrassModifier {
  return value === 'swamp' || value === 'dark_forest' ? value : 'none';
}

export function parseBiome(json: unknown): Biome | null {
  if (!isRecord(json)) return null;
  const temperature = numberField(json, 'temperature');
  const downfall = numberField(json, 'downfall');
  if (temperature === null || downfall === null) return null;
  const effects: JsonRecord = recordField(json, 'effects') ?? {};
  const attributes: JsonRecord = recordField(json, 'attributes') ?? {};
  return {
    temperature,
    downfall,
    grassColor: colorValue(effects['grass_color']),
    foliageColor: colorValue(effects['foliage_color']),
    waterColor: colorValue(effects['water_color']) ?? 0x3f76e4,
    grassModifier: grassModifier(stringField(effects, 'grass_color_modifier')),
    skyColor: colorValue(effects['sky_color']) ?? colorValue(attributes['minecraft:visual/sky_color']),
    fogColor: colorValue(effects['fog_color']) ?? colorValue(attributes['minecraft:visual/fog_color']),
  };
}

export class BiomeTable {
  private readonly loaded: ReadonlyMap<string, Biome>;
  private readonly cache = new Map<string, Biome>();

  constructor(loaded: ReadonlyMap<string, Biome>) {
    this.loaded = loaded;
  }

  get(key: string | null): Biome {
    const bare = key === null || key === '' ? DEFAULT_KEY : bareName(key);
    const cached = this.cache.get(bare);
    if (cached !== undefined) return cached;
    const modern = LEGACY_BIOME_KEYS[bare] ?? bare;
    const known = KNOWN_BIOMES[modern] ?? KNOWN_BIOMES[DEFAULT_KEY];
    const biome = this.loaded.get(bare) ?? this.loaded.get(modern) ?? (known === undefined ? null : rowBiome(KNOWN_BIOMES[modern] === undefined ? DEFAULT_KEY : modern, known)) ?? this.fallback();
    this.cache.set(bare, biome);
    return biome;
  }

  private fallback(): Biome {
    return { temperature: 0.8, downfall: 0.4, grassColor: null, foliageColor: null, waterColor: 0x3f76e4, grassModifier: 'none', skyColor: DEFAULT_SKY_COLOR, fogColor: null };
  }
}
