import type { AtlasSprite } from './Atlas.ts';
import { type BakedBlock, type BlockLibrary, MISSING_SPRITE } from './BlockLibrary.ts';
import type { BlockState } from '../../format/BlockState.ts';
import { DIRECTIONS, DIRECTION_INDEX, PLANE_AXES } from './Direction.ts';
import type { BakedModel, BakedQuad } from './ModelBaker.ts';
import { type TintRule, tintRule } from './Tints.ts';

export type RenderLayer = 'solid' | 'cutout' | 'translucent';

export const FULL_MASK = 0xffff;

export interface PreparedQuad {
  readonly quad: BakedQuad;
  readonly sprite: AtlasSprite;
  readonly layer: RenderLayer;
  readonly footprint: number;
}

export interface PreparedModel {
  readonly quads: readonly PreparedQuad[];
  readonly ambientOcclusion: boolean;
  readonly occlusion: Uint16Array;
  readonly shape: Uint16Array;
}

export interface BlockRender {
  readonly block: BakedBlock;
  readonly tint: TintRule;
  readonly parts: readonly (readonly { readonly model: PreparedModel; readonly weight: number }[])[];
  readonly occlusion: Uint16Array;
  readonly fullShape: boolean;
  readonly opaque: boolean;
  readonly selfCulling: boolean;
  readonly air: boolean;
}

export type SpriteLookup = (id: string) => AtlasSprite | null;

export function footprintMask(quad: BakedQuad, covering: boolean): number {
  const [a, b] = PLANE_AXES[quad.direction];
  let minA = Infinity;
  let maxA = -Infinity;
  let minB = Infinity;
  let maxB = -Infinity;
  for (let vertex = 0; vertex < 4; vertex++) {
    const va = quad.positions[vertex * 3 + a] ?? 0;
    const vb = quad.positions[vertex * 3 + b] ?? 0;
    minA = Math.min(minA, va);
    maxA = Math.max(maxA, va);
    minB = Math.min(minB, vb);
    maxB = Math.max(maxB, vb);
  }
  let mask = 0;
  for (let cellB = 0; cellB < 4; cellB++) {
    for (let cellA = 0; cellA < 4; cellA++) {
      const lowA = cellA / 4;
      const highA = (cellA + 1) / 4;
      const lowB = cellB / 4;
      const highB = (cellB + 1) / 4;
      const inside = covering
        ? minA <= lowA + 1e-4 && maxA >= highA - 1e-4 && minB <= lowB + 1e-4 && maxB >= highB - 1e-4
        : maxA > lowA + 1e-4 && minA < highA - 1e-4 && maxB > lowB + 1e-4 && minB < highB - 1e-4;
      if (inside) mask |= 1 << (cellB * 4 + cellA);
    }
  }
  return mask;
}

export function shapeMasks(models: readonly BakedModel[]): Uint16Array {
  const shape = new Uint16Array(6);
  for (const model of models) {
    for (const quad of model.quads) {
      if (!quad.flush) continue;
      const index = DIRECTION_INDEX[quad.direction];
      shape[index] = (shape[index] ?? 0) | footprintMask(quad, true);
    }
  }
  return shape;
}

export function isFullShape(shape: Uint16Array): boolean {
  return DIRECTIONS.every((direction) => shape[DIRECTION_INDEX[direction]] === FULL_MASK);
}

export function isFullCube(block: BakedBlock): boolean {
  return isFullShape(shapeMasks(block.parts.flatMap((options) => (options[0] === undefined ? [] : [options[0].model]))));
}

export class BlockRenders {
  private readonly library: BlockLibrary;
  private readonly sprites: SpriteLookup;
  private readonly states: readonly BlockState[];
  private readonly renders: (BlockRender | undefined)[] = [];
  private readonly models = new WeakMap<BakedModel, PreparedModel>();

  constructor(library: BlockLibrary, states: readonly BlockState[], sprites: SpriteLookup) {
    this.library = library;
    this.states = states;
    this.sprites = sprites;
  }

  get(index: number): BlockRender {
    const cached = this.renders[index];
    if (cached !== undefined) return cached;
    const state = this.states[index] ?? { name: 'air', properties: {} };
    const render = this.build(this.library.bake(state));
    this.renders[index] = render;
    return render;
  }

  private prepare(model: BakedModel): PreparedModel {
    const cached = this.models.get(model);
    if (cached !== undefined) return cached;
    const occlusion = new Uint16Array(6);
    const shape = new Uint16Array(6);
    const quads: PreparedQuad[] = [];
    for (const quad of model.quads) {
      const sprite = this.sprites(quad.sprite) ?? this.sprites(MISSING_SPRITE);
      if (sprite === null) continue;
      const layer: RenderLayer = sprite.alpha;
      quads.push({ quad, sprite, layer, footprint: footprintMask(quad, false) });
      if (quad.flush) {
        const covered = footprintMask(quad, true);
        const index = DIRECTION_INDEX[quad.direction];
        shape[index] = (shape[index] ?? 0) | covered;
        if (layer === 'solid') occlusion[index] = (occlusion[index] ?? 0) | covered;
      }
    }
    const prepared = { quads, ambientOcclusion: model.ambientOcclusion, occlusion, shape };
    this.models.set(model, prepared);
    return prepared;
  }

  private build(block: BakedBlock): BlockRender {
    const parts = block.parts.map((options) => options.map((option) => ({ model: this.prepare(option.model), weight: option.weight })));
    const occlusion = new Uint16Array(6);
    const shape = new Uint16Array(6);
    for (const options of parts) {
      const first = options[0];
      if (first === undefined) continue;
      for (let direction = 0; direction < 6; direction++) {
        occlusion[direction] = (occlusion[direction] ?? 0) | (first.model.occlusion[direction] ?? 0);
        shape[direction] = (shape[direction] ?? 0) | (first.model.shape[direction] ?? 0);
      }
    }
    const fullShape = isFullShape(shape);
    const opaque = DIRECTIONS.every((direction) => occlusion[DIRECTION_INDEX[direction]] === FULL_MASK);
    const name = block.state.name;
    return {
      block,
      tint: tintRule(block.state),
      parts,
      occlusion,
      fullShape,
      opaque,
      selfCulling: !opaque && fullShape && !name.endsWith('_leaves'),
      air: name === 'air' || name === 'cave_air' || name === 'void_air',
    };
  }
}
