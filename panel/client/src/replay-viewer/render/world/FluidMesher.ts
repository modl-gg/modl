import type { AtlasSprite } from './Atlas.ts';
import type { FluidKind } from './BlockLibrary.ts';
import type { BlockRender, RenderLayer } from './BlockRenders.ts';
import { type Direction, DIRECTION_INDEX, FACE_SHADE, OPPOSITE } from './Direction.ts';
import { LIGHT_SCALE, type MeshBuilder, type QuadVertex } from './MeshBuilder.ts';
import type { MesherContext } from './SectionMesher.ts';
import type { BiomeTints } from './BiomeTints.ts';

export const FLUID_SPRITES: Readonly<Record<FluidKind, readonly [string, string]>> = {
  water: ['block/water_still', 'block/water_flow'],
  lava: ['block/lava_still', 'block/lava_flow'],
};

const SURFACE_OFFSET = 0.001;
const HORIZONTAL: readonly (readonly [Direction, number, number])[] = [
  ['north', 0, -1],
  ['south', 0, 1],
  ['west', -1, 0],
  ['east', 1, 0],
];

function maxLight(left: number, right: number): number {
  return (Math.max(left >> 4, right >> 4) << 4) | Math.max(left & 15, right & 15);
}

function ownHeight(level: number): number {
  return level >= 8 ? 8 / 9 : (8 - level) / 9;
}

function weighted(accumulator: [number, number], height: number): void {
  if (height >= 0.8) {
    accumulator[0] += height * 10;
    accumulator[1] += 10;
  } else if (height >= 0) {
    accumulator[0] += height;
    accumulator[1] += 1;
  }
}

export class FluidMesher {
  private readonly context: MesherContext;
  private readonly layers: Record<RenderLayer, MeshBuilder>;
  private readonly vertices: QuadVertex[] = Array.from({ length: 4 }, () => ({ x: 0, y: 0, z: 0, u: 0, v: 0, r: 1, g: 1, b: 1, sky: 15, block: 0, ao: 1 }));

  private readonly tints: BiomeTints;

  constructor(context: MesherContext, layers: Record<RenderLayer, MeshBuilder>, tints: BiomeTints) {
    this.context = context;
    this.layers = layers;
    this.tints = tints;
  }

  private renderAt(x: number, y: number, z: number): BlockRender | null {
    const index = this.context.world.state(x, y, z);
    return index === 0 ? null : this.context.renders.get(index);
  }

  private sameFluid(fluid: FluidKind, x: number, y: number, z: number): boolean {
    return this.renderAt(x, y, z)?.block.fluid === fluid;
  }

  private heightAt(fluid: FluidKind, x: number, y: number, z: number): number {
    const render = this.renderAt(x, y, z);
    if (render?.block.fluid === fluid) {
      if (this.sameFluid(fluid, x, y + 1, z)) return 1;
      return ownHeight(render.block.fluidLevel);
    }
    if (render === null || render.air || !render.fullShape) return 0;
    return -1;
  }

  private cornerHeight(fluid: FluidKind, self: number, x: number, y: number, z: number, dx: number, dz: number): number {
    const sideA = this.heightAt(fluid, x + dx, y, z);
    const sideB = this.heightAt(fluid, x, y, z + dz);
    if (sideA >= 1 || sideB >= 1) return 1;
    const accumulator: [number, number] = [0, 0];
    if (sideA > 0 || sideB > 0) {
      const diagonal = this.heightAt(fluid, x + dx, y, z + dz);
      if (diagonal >= 1) return 1;
      weighted(accumulator, diagonal);
    }
    weighted(accumulator, self);
    weighted(accumulator, sideA);
    weighted(accumulator, sideB);
    return accumulator[1] === 0 ? self : accumulator[0] / accumulator[1];
  }

  private occludes(x: number, y: number, z: number, face: Direction): boolean {
    const render = this.renderAt(x, y, z);
    if (render === null) return false;
    return (render.occlusion[DIRECTION_INDEX[face]] ?? 0) === 0xffff;
  }

  mesh(render: BlockRender, x: number, y: number, z: number, localX: number, localY: number, localZ: number): void {
    const fluid = render.block.fluid;
    if (fluid === null) return;
    const [stillId, flowId] = FLUID_SPRITES[fluid];
    const still = this.context.sprite(stillId);
    const flow = this.context.sprite(flowId) ?? still;
    if (still === null || flow === null) return;
    const layer: RenderLayer = fluid === 'water' ? 'translucent' : 'solid';
    const tint = fluid === 'water' ? this.waterTint(x, y, z) : 0xffffff;
    const self = this.heightAt(fluid, x, y, z);
    const sameAbove = this.sameFluid(fluid, x, y + 1, z);
    const heights = self >= 1
      ? [1, 1, 1, 1]
      : [
          this.cornerHeight(fluid, self, x, y, z, -1, -1),
          this.cornerHeight(fluid, self, x, y, z, -1, 1),
          this.cornerHeight(fluid, self, x, y, z, 1, 1),
          this.cornerHeight(fluid, self, x, y, z, 1, -1),
        ];
    const [hNw, hSw, hSe, hNe] = heights.map((height) => height - (sameAbove ? 0 : SURFACE_OFFSET));
    const ownLight = this.context.world.light(x, y, z);
    const lavaLight = fluid === 'lava' ? 15 : 0;
    if (!sameAbove) {
      const light = maxLight(ownLight, this.context.world.light(x, y + 1, z));
      const uvs = this.topUvs(fluid, x, y, z, self);
      const sprite = uvs === null ? still : flow;
      const corners: [number, number, number][] = [[0, hNw ?? 0, 0], [0, hSw ?? 0, 1], [1, hSe ?? 0, 1], [1, hNe ?? 0, 0]];
      const uvPairs = uvs ?? [[0, 0], [0, 1], [1, 1], [1, 0]];
      this.emit(layer, sprite, corners, uvPairs, FACE_SHADE.up, tint, light, lavaLight, localX, localY, localZ);
      if (fluid === 'water') {
        const reversed: [number, number, number][] = [corners[0] ?? [0, 0, 0], corners[3] ?? [0, 0, 0], corners[2] ?? [0, 0, 0], corners[1] ?? [0, 0, 0]];
        const reversedUv = [uvPairs[0] ?? [0, 0], uvPairs[3] ?? [0, 0], uvPairs[2] ?? [0, 0], uvPairs[1] ?? [0, 0]];
        this.emit(layer, sprite, reversed, reversedUv, FACE_SHADE.up, tint, light, lavaLight, localX, localY, localZ);
      }
    }
    if (!this.sameFluid(fluid, x, y - 1, z) && !this.occludes(x, y - 1, z, 'up')) {
      const light = maxLight(ownLight, this.context.world.light(x, y - 1, z));
      this.emit(layer, still, [[0, SURFACE_OFFSET, 1], [0, SURFACE_OFFSET, 0], [1, SURFACE_OFFSET, 0], [1, SURFACE_OFFSET, 1]], [[0, 1], [0, 0], [1, 0], [1, 1]], FACE_SHADE.down, tint, light, lavaLight, localX, localY, localZ);
    }
    for (const [direction, dx, dz] of HORIZONTAL) {
      if (this.sameFluid(fluid, x + dx, y, z + dz)) continue;
      if (this.occludes(x + dx, y, z + dz, OPPOSITE[direction])) continue;
      const light = maxLight(ownLight, this.context.world.light(x + dx, y, z + dz));
      const side = this.sideCorners(direction, hNw ?? 0, hSw ?? 0, hSe ?? 0, hNe ?? 0);
      this.emit(layer, flow, side.corners, side.uvs, FACE_SHADE[direction], tint, light, lavaLight, localX, localY, localZ);
      if (fluid === 'water') this.emit(layer, flow, [...side.corners].reverse(), [...side.uvs].reverse(), FACE_SHADE[direction], tint, light, lavaLight, localX, localY, localZ);
    }
  }

  private sideCorners(direction: Direction, hNw: number, hSw: number, hSe: number, hNe: number): { corners: [number, number, number][]; uvs: [number, number][] } {
    const build = (left: [number, number, number], right: [number, number, number]): { corners: [number, number, number][]; uvs: [number, number][] } => ({
      corners: [left, [left[0], 0, left[2]], [right[0], 0, right[2]], right],
      uvs: [[0, (1 - left[1]) * 0.5], [0, 0.5], [0.5, 0.5], [0.5, (1 - right[1]) * 0.5]],
    });
    switch (direction) {
      case 'north':
        return build([1, hNe, 0], [0, hNw, 0]);
      case 'south':
        return build([0, hSw, 1], [1, hSe, 1]);
      case 'west':
        return build([0, hNw, 0], [0, hSw, 1]);
      case 'east':
        return build([1, hSe, 1], [1, hNe, 0]);
      case 'up':
      case 'down':
        return build([0, 0, 0], [0, 0, 0]);
    }
  }

  private topUvs(fluid: FluidKind, x: number, y: number, z: number, self: number): [number, number][] | null {
    let flowX = 0;
    let flowZ = 0;
    for (const [, dx, dz] of HORIZONTAL) {
      const neighbor = this.heightAt(fluid, x + dx, y, z + dz);
      if (neighbor < 0) continue;
      const difference = neighbor === 0 ? (this.sameFluid(fluid, x + dx, y - 1, z + dz) ? self : 0) : self - neighbor;
      flowX += dx * difference;
      flowZ += dz * difference;
    }
    if (Math.abs(flowX) < 1e-4 && Math.abs(flowZ) < 1e-4) return null;
    const angle = Math.atan2(flowZ, flowX) - Math.PI / 2;
    const s = Math.sin(angle) * 0.25;
    const c = Math.cos(angle) * 0.25;
    return [
      [0.5 + (-c - s), 0.5 + (-c + s)],
      [0.5 + (-c + s), 0.5 + (c + s)],
      [0.5 + (c + s), 0.5 + (c - s)],
      [0.5 + (c - s), 0.5 + (-c - s)],
    ];
  }

  private waterTint(x: number, y: number, z: number): number {
    return this.tints.color('water', x, y, z);
  }

  private emit(layer: RenderLayer, sprite: AtlasSprite, corners: readonly (readonly [number, number, number])[], uvs: readonly (readonly [number, number])[], shade: number, tint: number, light: number, minimumBlock: number, localX: number, localY: number, localZ: number): void {
    const atlas = this.context.atlasSize;
    const red = ((tint >> 16) & 255) / 255 * shade;
    const green = ((tint >> 8) & 255) / 255 * shade;
    const blue = (tint & 255) / 255 * shade;
    for (let index = 0; index < 4; index++) {
      const vertex = this.vertices[index];
      const corner = corners[index];
      const uv = uvs[index];
      if (vertex === undefined || corner === undefined || uv === undefined) continue;
      vertex.x = localX + corner[0];
      vertex.y = localY + corner[1];
      vertex.z = localZ + corner[2];
      vertex.u = (sprite.x + uv[0] * sprite.width) / atlas;
      vertex.v = (sprite.y + uv[1] * sprite.height) / atlas;
      vertex.r = red;
      vertex.g = green;
      vertex.b = blue;
      vertex.ao = 1;
      vertex.sky = (light >> 4) * LIGHT_SCALE;
      vertex.block = Math.max(light & 15, minimumBlock) * LIGHT_SCALE;
    }
    this.layers[layer].addQuad(this.vertices, sprite.animationSlot);
  }
}
