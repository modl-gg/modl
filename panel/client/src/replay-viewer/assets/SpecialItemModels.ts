import { bareName } from '../format/ids.ts';
import type { Direction } from '../render/world/Direction.ts';
import type { BakedQuad } from '../render/world/ModelBaker.ts';

type Triple = readonly [number, number, number];

interface SpecialCube {
  readonly uv: readonly [number, number];
  readonly from: Triple;
  readonly size: Triple;
  readonly inflate?: number;
}

export interface SpecialItemModel {
  readonly textures: readonly string[];
  readonly textureSize: readonly [number, number];
  readonly cubes: readonly SpecialCube[];
  readonly flip: Triple;
}

const CHEST: readonly SpecialCube[] = [
  { uv: [0, 19], from: [1, 0, 1], size: [14, 10, 14] },
  { uv: [0, 0], from: [1, 9, 1], size: [14, 5, 14] },
  { uv: [0, 0], from: [7, 6, 14], size: [2, 4, 1] },
];
const HEAD: readonly SpecialCube[] = [{ uv: [0, 0], from: [-4, -8, -4], size: [8, 8, 8] }];
const PLAYER_HEAD: readonly SpecialCube[] = [...HEAD, { uv: [32, 0], from: [-4, -8, -4], size: [8, 8, 8], inflate: 0.25 }];
const SHIELD: readonly SpecialCube[] = [
  { uv: [0, 0], from: [-6, -11, -2], size: [12, 22, 1] },
  { uv: [26, 0], from: [-1, -3, -1], size: [2, 6, 6] },
];

function chest(name: string): SpecialItemModel {
  return { textures: [`entity/chest/${name}`], textureSize: [64, 64], cubes: CHEST, flip: [1, 1, 1] };
}

function head(textures: readonly string[], height: number, cubes: readonly SpecialCube[] = HEAD): SpecialItemModel {
  return { textures, textureSize: [64, height], cubes, flip: [-1, -1, 1] };
}

const SPECIAL_ITEMS: Readonly<Record<string, SpecialItemModel>> = {
  chest: chest('normal'),
  trapped_chest: chest('trapped'),
  ender_chest: chest('ender'),
  shield: { textures: ['entity/shield/shield_base_nopattern', 'entity/shield_base_nopattern'], textureSize: [64, 64], cubes: SHIELD, flip: [1, -1, -1] },
  skeleton_skull: head(['entity/skeleton/skeleton'], 32),
  wither_skeleton_skull: head(['entity/skeleton/wither_skeleton'], 32),
  zombie_head: head(['entity/zombie/zombie'], 64),
  creeper_head: head(['entity/creeper/creeper'], 32),
  player_head: head(['entity/player/wide/steve', 'entity/steve'], 64, PLAYER_HEAD),
};

export const SPECIAL_ITEM_TEXTURES: readonly string[] = [...new Set(Object.values(SPECIAL_ITEMS).flatMap((model) => model.textures))];

export function specialItemModel(itemKey: string): SpecialItemModel | null {
  return SPECIAL_ITEMS[bareName(itemKey)] ?? null;
}

type Corner = readonly [0 | 1, 0 | 1, 0 | 1];

const FACES: readonly { readonly direction: Direction; readonly corners: readonly Corner[]; readonly rect: (u: number, v: number, w: number, h: number, d: number) => readonly [number, number, number, number] }[] = [
  { direction: 'down', corners: [[1, 0, 1], [0, 0, 1], [0, 0, 0], [1, 0, 0]], rect: (u, v, w, _h, d) => [u + d, v, u + d + w, v + d] },
  { direction: 'up', corners: [[1, 1, 0], [0, 1, 0], [0, 1, 1], [1, 1, 1]], rect: (u, v, w, _h, d) => [u + d + w, v + d, u + d + w * 2, v] },
  { direction: 'west', corners: [[0, 0, 0], [0, 0, 1], [0, 1, 1], [0, 1, 0]], rect: (u, v, _w, h, d) => [u, v + d, u + d, v + d + h] },
  { direction: 'north', corners: [[1, 0, 0], [0, 0, 0], [0, 1, 0], [1, 1, 0]], rect: (u, v, w, h, d) => [u + d, v + d, u + d + w, v + d + h] },
  { direction: 'east', corners: [[1, 0, 1], [1, 0, 0], [1, 1, 0], [1, 1, 1]], rect: (u, v, w, h, d) => [u + d + w, v + d, u + d * 2 + w, v + d + h] },
  { direction: 'south', corners: [[0, 0, 1], [1, 0, 1], [1, 1, 1], [0, 1, 1]], rect: (u, v, w, h, d) => [u + d * 2 + w, v + d, u + d * 2 + w * 2, v + d + h] },
];

const FLIPPED: Readonly<Record<Direction, Partial<Record<'x' | 'y' | 'z', Direction>>>> = {
  down: { y: 'up' },
  up: { y: 'down' },
  west: { x: 'east' },
  east: { x: 'west' },
  north: { z: 'south' },
  south: { z: 'north' },
};

function flippedDirection(direction: Direction, flip: Triple): Direction {
  const axis = direction === 'west' || direction === 'east' ? 'x' : direction === 'down' || direction === 'up' ? 'y' : 'z';
  const sign = axis === 'x' ? flip[0] : axis === 'y' ? flip[1] : flip[2];
  return sign < 0 ? FLIPPED[direction][axis] ?? direction : direction;
}

export function specialItemQuads(model: SpecialItemModel, sprite: string): BakedQuad[] {
  const quads: BakedQuad[] = [];
  const points: number[] = [];
  for (const cube of model.cubes) {
    const inflate = cube.inflate ?? 0;
    const [w, h, d] = cube.size;
    const low: Triple = [cube.from[0] - inflate, cube.from[1] - inflate, cube.from[2] - inflate];
    const high: Triple = [cube.from[0] + w + inflate, cube.from[1] + h + inflate, cube.from[2] + d + inflate];
    for (const face of FACES) {
      const [u0, v0, u1, v1] = face.rect(cube.uv[0], cube.uv[1], w, h, d);
      const positions = new Float32Array(12);
      face.corners.forEach((corner, index) => {
        for (let axis = 0; axis < 3; axis++) {
          const value = (corner[axis] === 0 ? low[axis] : high[axis]) ?? 0;
          positions[index * 3 + axis] = value * (model.flip[axis] ?? 1);
          points.push(positions[index * 3 + axis] ?? 0);
        }
      });
      const uvs = new Float32Array([u1, v0, u0, v0, u0, v1, u1, v1].map((value, index) => value / (model.textureSize[index % 2] ?? 64)));
      quads.push({ positions, uvs, sprite, direction: flippedDirection(face.direction, model.flip), cullface: null, tintindex: -1, shadeDirection: null, emission: 0, flush: false });
    }
  }
  const bounds = [0, 1, 2].map((axis) => {
    const values = points.filter((_, index) => index % 3 === axis);
    return [Math.min(...values), Math.max(...values)];
  });
  const scale = 1 / 16;
  for (const quad of quads) {
    for (let vertex = 0; vertex < 4; vertex++) {
      for (let axis = 0; axis < 3; axis++) {
        const range = bounds[axis] ?? [0, 0];
        const center = ((range[0] ?? 0) + (range[1] ?? 0)) / 2;
        const value = quad.positions[vertex * 3 + axis] ?? 0;
        quad.positions[vertex * 3 + axis] = (value - center) * scale + 0.5;
      }
    }
  }
  return quads;
}
