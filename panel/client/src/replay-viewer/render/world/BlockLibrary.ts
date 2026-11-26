import { DIRECTIONS } from './Direction.ts';
import { type BakedModel, bakeModel } from './ModelBaker.ts';
import { type ModelLookup, ModelResolver } from './ModelResolver.ts';
import type { BlockState } from '../../format/BlockState.ts';
import {
  type BlockStateDefinition,
  type ModelVariant,
  parseBlockStateDefinition,
} from './BlockStates.ts';

export interface WeightedModel {
  readonly model: BakedModel;
  readonly weight: number;
}

export type FluidKind = 'water' | 'lava';

export interface BakedBlock {
  readonly state: BlockState;
  readonly parts: readonly (readonly WeightedModel[])[];
  readonly fluid: FluidKind | null;
  readonly fluidLevel: number;
}

export interface BlockSource {
  readonly blockstate: (name: string) => unknown;
  readonly model: ModelLookup;
}

const RENAMES: readonly (readonly [string, string])[] = [
  ['short_grass', 'grass'],
  ['dirt_path', 'grass_path'],
  ['iron_chain', 'chain'],
  ['oak_sign', 'sign'],
  ['oak_wall_sign', 'wall_sign'],
  ['smooth_stone_slab', 'stone_slab'],
  ['zombified_piglin_spawn_egg', 'zombie_pigman_spawn_egg'],
];

const AIR = new Set(['air', 'cave_air', 'void_air']);
const UNKNOWN_BLOCK = 'modl_unknown';
export const MISSING_SPRITE = 'modl:missing';
export const UNKNOWN_SPRITE = 'modl:unknown';

const ALWAYS_WATERLOGGED = new Set(['kelp', 'kelp_plant', 'seagrass', 'tall_seagrass', 'bubble_column']);

export function nameCandidates(name: string): string[] {
  const candidates = [name];
  for (const [current, previous] of RENAMES) {
    if (name === current) candidates.push(previous);
    if (name === previous) candidates.push(current);
  }
  return candidates;
}

function fluidOf(state: BlockState): { fluid: FluidKind | null; level: number } {
  const level = Number.parseInt(state.properties['level'] ?? '0', 10);
  if (state.name === 'water' || state.name === 'flowing_water') return { fluid: 'water', level: Number.isFinite(level) ? level : 0 };
  if (state.name === 'lava' || state.name === 'flowing_lava') return { fluid: 'lava', level: Number.isFinite(level) ? level : 0 };
  if (state.properties['waterlogged'] === 'true' || ALWAYS_WATERLOGGED.has(state.name)) return { fluid: 'water', level: 0 };
  return { fluid: null, level: 0 };
}

export class BlockLibrary {
  private readonly source: BlockSource;
  private readonly resolver: ModelResolver;
  private readonly definitions = new Map<string, BlockStateDefinition | null>();
  private readonly baked = new Map<string, BakedModel>();

  constructor(source: BlockSource) {
    this.source = source;
    this.resolver = new ModelResolver(source.model);
  }

  bake(state: BlockState): BakedBlock {
    const { fluid, level } = fluidOf(state);
    const definition = this.definition(state.name);
    if (definition === null) {
      const placeholder = fluid === null && !AIR.has(state.name);
      return { state, parts: placeholder ? [[{ model: this.placeholder(state.name), weight: 1 }]] : [], fluid, fluidLevel: level };
    }
    const parts = definition.parts(state.properties).map((variants) => variants.map((variant) => ({ model: this.bakeVariant(variant), weight: variant.weight })));
    if (parts.length === 0 && fluid === null && !AIR.has(state.name) && !definition.multipart) {
      return { state, parts: [[{ model: this.placeholder(state.name), weight: 1 }]], fluid, fluidLevel: level };
    }
    return { state, parts, fluid, fluidLevel: level };
  }

  private placeholder(name: string): BakedModel {
    const sprite = name === UNKNOWN_BLOCK ? UNKNOWN_SPRITE : MISSING_SPRITE;
    const cached = this.baked.get(sprite);
    if (cached !== undefined) return cached;
    const faces = Object.fromEntries(DIRECTIONS.map((direction) => [direction, { texture: sprite, uv: null, cullface: direction, rotation: 0, tintindex: -1 }]));
    const model = bakeModel({
      id: sprite,
      elements: [{ from: [0, 0, 0], to: [16, 16, 16], rotation: null, shade: true, shadeDirection: null, lightEmission: 0, faces }],
      textures: {},
      ambientOcclusion: true,
      generatedItem: false,
      display: {},
    }, { x: 0, y: 0, uvlock: false });
    this.baked.set(sprite, model);
    return model;
  }

  allModels(name: string): BakedModel[] {
    return (this.definition(name)?.variants ?? []).map((variant) => this.bakeVariant(variant));
  }

  bakeModelId(modelId: string): BakedModel | null {
    const model = this.resolver.resolve(modelId);
    return model === null ? null : bakeModel(model, { x: 0, y: 0, uvlock: false });
  }

  resolveModel(modelId: string): ReturnType<ModelResolver['resolve']> {
    return this.resolver.resolve(modelId);
  }

  private definition(name: string): BlockStateDefinition | null {
    const cached = this.definitions.get(name);
    if (cached !== undefined) return cached;
    let definition: BlockStateDefinition | null = null;
    for (const candidate of nameCandidates(name)) {
      definition = parseBlockStateDefinition(this.source.blockstate(candidate));
      if (definition !== null) break;
    }
    this.definitions.set(name, definition);
    return definition;
  }

  private bakeVariant(variant: ModelVariant): BakedModel {
    const key = `${variant.model}|${String(variant.x)}|${String(variant.y)}|${String(variant.uvlock)}`;
    const cached = this.baked.get(key);
    if (cached !== undefined) return cached;
    const model = this.resolver.resolve(variant.model);
    const baked = model === null ? this.placeholder(variant.model) : bakeModel(model, variant);
    this.baked.set(key, baked);
    return baked;
  }
}
