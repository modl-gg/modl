import type { AtlasSprite } from './Atlas.ts';
import type { BiomeTable } from './Biomes.ts';
import type { BlockRender, BlockRenders, PreparedModel, PreparedQuad, RenderLayer } from './BlockRenders.ts';
import { pickWeighted, positionSeed } from './BlockStates.ts';
import type { BlockWorld } from './BlockWorld.ts';
import { type Direction, DIRECTION_INDEX, DIRECTION_VECTORS, FACE_SHADE, OPPOSITE, PLANE_AXES, type Vec3 } from './Direction.ts';
import { FluidMesher } from './FluidMesher.ts';
import { LIGHT_SCALE, type LayerMesh, MeshBuilder, type QuadVertex } from './MeshBuilder.ts';
import { BiomeTints } from './BiomeTints.ts';
import type { Colormaps } from './Tints.ts';

export interface SectionMesh {
  readonly solid: LayerMesh;
  readonly cutout: LayerMesh;
  readonly translucent: LayerMesh;
}

export interface MesherContext {
  readonly world: BlockWorld;
  readonly renders: BlockRenders;
  readonly biomes: BiomeTable;
  readonly biomeKeys: readonly string[];
  readonly colormaps: Colormaps;
  readonly atlasSize: number;
  readonly sprite: (id: string) => AtlasSprite | null;
  readonly legacyWater: boolean;
  readonly netherShading: boolean;
}

const NETHER_SHADE: Readonly<Record<Direction, number>> = { down: 0.9, up: 0.9, north: 0.8, south: 0.8, west: 0.6, east: 0.6 };

const AXIS_VECTORS: readonly Vec3[] = [[1, 0, 0], [0, 1, 0], [0, 0, 1]];

function isPaneLike(name: string): boolean {
  return name.endsWith('_pane') || name === 'iron_bars' || name.endsWith('_bars');
}

export class SectionMesher {
  private readonly context: MesherContext;
  private readonly fluids: FluidMesher;
  private readonly layers: Record<RenderLayer, MeshBuilder> = {
    solid: new MeshBuilder(),
    cutout: new MeshBuilder(),
    translucent: new MeshBuilder(),
  };
  private readonly vertices: QuadVertex[] = Array.from({ length: 4 }, () => ({ x: 0, y: 0, z: 0, u: 0, v: 0, r: 1, g: 1, b: 1, sky: 15, block: 0, ao: 1 }));
  private readonly cornerAo = new Float32Array(4);
  private readonly cornerSky = new Float32Array(4);
  private readonly cornerBlock = new Float32Array(4);
  private readonly weights = new Float32Array(4);
  private readonly tints: BiomeTints;

  constructor(context: MesherContext) {
    this.context = context;
    this.tints = new BiomeTints(context);
    this.fluids = new FluidMesher(context, this.layers, this.tints);
  }

  mesh(sectionX: number, sectionY: number, sectionZ: number): SectionMesh {
    for (const builder of Object.values(this.layers)) builder.reset();
    this.tints.clear();
    const world = this.context.world;
    const states = world.section(sectionX, sectionY, sectionZ)?.states ?? null;
    if (states !== null) {
      const originX = sectionX * 16;
      const originY = sectionY * 16;
      const originZ = sectionZ * 16;
      for (let index = 0; index < 4096; index++) {
        const stateIndex = states[index] ?? 0;
        if (stateIndex === 0) continue;
        const render = this.context.renders.get(stateIndex);
        if (render.air) continue;
        const localX = index & 15;
        const localZ = (index >> 4) & 15;
        const localY = index >> 8;
        const x = originX + localX;
        const y = originY + localY;
        const z = originZ + localZ;
        this.meshBlock(render, x, y, z, localX, localY, localZ);
        if (render.block.fluid !== null) this.fluids.mesh(render, x, y, z, localX, localY, localZ);
      }
    }
    return {
      solid: this.layers.solid.build(),
      cutout: this.layers.cutout.build(),
      translucent: this.layers.translucent.build(),
    };
  }

  private meshBlock(render: BlockRender, x: number, y: number, z: number, localX: number, localY: number, localZ: number): void {
    if (render.parts.length === 0) return;
    const seed = positionSeed(x, y, z);
    for (const options of render.parts) {
      const model = pickWeighted(options, seed)?.model ?? null;
      if (model === null) continue;
      for (const prepared of model.quads) {
        if (prepared.quad.cullface !== null && this.culled(render, prepared, x, y, z, prepared.quad.cullface)) continue;
        this.emitQuad(render, model, prepared, x, y, z, localX, localY, localZ);
      }
    }
  }

  private culled(render: BlockRender, prepared: PreparedQuad, x: number, y: number, z: number, cullface: Direction): boolean {
    const offset = DIRECTION_VECTORS[cullface];
    const neighborIndex = this.context.world.state(x + offset[0], y + offset[1], z + offset[2]);
    if (neighborIndex === 0) return false;
    const neighbor = this.context.renders.get(neighborIndex);
    if (neighbor.air) return false;
    const name = render.block.state.name;
    if (neighbor.block.state.name === name && (render.selfCulling || isPaneLike(name))) return true;
    const cover = neighbor.occlusion[DIRECTION_INDEX[OPPOSITE[cullface]]] ?? 0;
    return (prepared.footprint & ~cover) === 0 && prepared.footprint !== 0;
  }

  private shadeOf(x: number, y: number, z: number): number {
    const index = this.context.world.state(x, y, z);
    if (index === 0) return 1;
    return this.context.renders.get(index).fullShape ? 0.2 : 1;
  }

  private transparentAt(x: number, y: number, z: number): boolean {
    const index = this.context.world.state(x, y, z);
    return index === 0 || !this.context.renders.get(index).opaque;
  }

  private computeCorners(direction: Direction, baseX: number, baseY: number, baseZ: number, useAo: boolean): void {
    const world = this.context.world;
    const [axisA, axisB] = PLANE_AXES[direction];
    const normal = DIRECTION_VECTORS[direction];
    const vectorA = AXIS_VECTORS[axisA] ?? [1, 0, 0];
    const vectorB = AXIS_VECTORS[axisB] ?? [0, 1, 0];
    const center = world.light(baseX, baseY, baseZ);
    const centerShade = this.shadeOf(baseX, baseY, baseZ);
    for (let corner = 0; corner < 4; corner++) {
      const signA = (corner & 1) === 0 ? -1 : 1;
      const signB = (corner & 2) === 0 ? -1 : 1;
      const ax = vectorA[0] * signA;
      const ay = vectorA[1] * signA;
      const az = vectorA[2] * signA;
      const bx = vectorB[0] * signB;
      const by = vectorB[1] * signB;
      const bz = vectorB[2] * signB;
      const side1Light = world.light(baseX + ax, baseY + ay, baseZ + az);
      const side2Light = world.light(baseX + bx, baseY + by, baseZ + bz);
      const cornerOpen = this.transparentAt(baseX + ax + normal[0], baseY + ay + normal[1], baseZ + az + normal[2])
        || this.transparentAt(baseX + bx + normal[0], baseY + by + normal[1], baseZ + bz + normal[2]);
      const diagonalLight = cornerOpen ? world.light(baseX + ax + bx, baseY + ay + by, baseZ + az + bz) : side1Light;
      this.blendInto(corner, side1Light, side2Light, diagonalLight, center);
      if (useAo) {
        const shade1 = this.shadeOf(baseX + ax, baseY + ay, baseZ + az);
        const shade2 = this.shadeOf(baseX + bx, baseY + by, baseZ + bz);
        const shadeCorner = cornerOpen ? this.shadeOf(baseX + ax + bx, baseY + ay + by, baseZ + az + bz) : shade1;
        this.cornerAo[corner] = (shade1 + shade2 + shadeCorner + centerShade) / 4;
      } else {
        this.cornerAo[corner] = 1;
      }
    }
  }

  private blendInto(corner: number, side1: number, side2: number, diagonal: number, center: number): void {
    const a = side1 === 0 ? center : side1;
    const b = side2 === 0 ? center : side2;
    const c = diagonal === 0 ? center : diagonal;
    this.cornerSky[corner] = ((a >> 4) + (b >> 4) + (c >> 4) + (center >> 4)) / 4;
    this.cornerBlock[corner] = ((a & 15) + (b & 15) + (c & 15) + (center & 15)) / 4;
  }

  private tintColor(render: BlockRender, x: number, y: number, z: number): number {
    const kind = render.tint.kind;
    switch (kind) {
      case 'none':
        return 0xffffff;
      case 'constant':
      case 'redstone':
      case 'stem':
        return render.tint.color;
      case 'grass':
      case 'foliage':
      case 'dry_foliage':
      case 'water':
        return this.tints.color(kind, x, y, z);
    }
  }

  private emitQuad(render: BlockRender, model: PreparedModel, prepared: PreparedQuad, x: number, y: number, z: number, localX: number, localY: number, localZ: number): void {
    const quad = prepared.quad;
    const direction = quad.direction;
    const normal = DIRECTION_VECTORS[direction];
    const offset = quad.flush ? 1 : 0;
    const useAo = model.ambientOcclusion && quad.emission === 0;
    this.computeCorners(direction, x + normal[0] * offset, y + normal[1] * offset, z + normal[2] * offset, useAo);
    const shadeTable = this.context.netherShading ? NETHER_SHADE : FACE_SHADE;
    const shade = shadeTable[quad.shadeDirection ?? direction];
    const tint = quad.tintindex >= 0 ? this.tintColor(render, x, y, z) : 0xffffff;
    const red = ((tint >> 16) & 255) / 255 * shade;
    const green = ((tint >> 8) & 255) / 255 * shade;
    const blue = (tint & 255) / 255 * shade;
    const [axisA, axisB] = PLANE_AXES[direction];
    const sprite = prepared.sprite;
    const atlas = this.context.atlasSize;
    for (let index = 0; index < 4; index++) {
      const vertex = this.vertices[index];
      if (vertex === undefined) continue;
      const px = quad.positions[index * 3] ?? 0;
      const py = quad.positions[index * 3 + 1] ?? 0;
      const pz = quad.positions[index * 3 + 2] ?? 0;
      const fa = Math.min(1, Math.max(0, axisA === 0 ? px : axisA === 1 ? py : pz));
      const fb = Math.min(1, Math.max(0, axisB === 0 ? px : axisB === 1 ? py : pz));
      const weights = this.weights;
      weights[0] = (1 - fa) * (1 - fb);
      weights[1] = fa * (1 - fb);
      weights[2] = (1 - fa) * fb;
      weights[3] = fa * fb;
      let ao = 0;
      let sky = 0;
      let block = 0;
      for (let corner = 0; corner < 4; corner++) {
        const weight = weights[corner] ?? 0;
        ao += (this.cornerAo[corner] ?? 1) * weight;
        sky += (this.cornerSky[corner] ?? 15) * weight;
        block += (this.cornerBlock[corner] ?? 0) * weight;
      }
      vertex.x = localX + px;
      vertex.y = localY + py;
      vertex.z = localZ + pz;
      vertex.u = (sprite.x + (quad.uvs[index * 2] ?? 0) * sprite.width) / atlas;
      vertex.v = (sprite.y + (quad.uvs[index * 2 + 1] ?? 0) * sprite.height) / atlas;
      vertex.r = red;
      vertex.g = green;
      vertex.b = blue;
      vertex.ao = ao;
      vertex.sky = Math.round(sky * LIGHT_SCALE);
      vertex.block = Math.round(Math.max(block, quad.emission) * LIGHT_SCALE);
    }
    this.layers[prepared.layer].addQuad(this.vertices, sprite.animationSlot);
  }
}
