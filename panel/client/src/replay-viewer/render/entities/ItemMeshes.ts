import { BufferGeometry, Float32BufferAttribute, Group, Mesh, type Object3D, type RawShaderMaterial, type Texture } from 'three';
import type { ResourcePack } from '../../assets/ResourcePack.ts';
import type { AtlasSprite } from '../world/Atlas.ts';
import { type BlockLibrary, MISSING_SPRITE } from '../world/BlockLibrary.ts';
import { DIRECTION_VECTORS } from '../world/Direction.ts';
import { itemModelId } from '../world/ItemModels.ts';
import type { BakedQuad } from '../world/ModelBaker.ts';
import { type DisplayTransform, type ResolvedModel, resolveTexture } from '../world/ModelResolver.ts';
import type { SharedUniforms } from '../WorldMaterials.ts';
import { createEntityMaterial } from './EntityMaterial.ts';

export interface AtlasView {
  readonly size: number;
  readonly pixels: Uint8Array;
  readonly sprites: ReadonlyMap<string, AtlasSprite>;
  readonly texture: Texture;
}

export type DisplayContext = 'thirdperson_righthand' | 'thirdperson_lefthand' | 'ground' | 'head' | 'fixed';

const DEG = Math.PI / 180;
const DEFAULT_DISPLAYS: Readonly<Record<DisplayContext, DisplayTransform>> = {
  thirdperson_righthand: { rotation: [0, 0, 0], translation: [0, 3, 1], scale: [0.55, 0.55, 0.55] },
  thirdperson_lefthand: { rotation: [0, 0, 0], translation: [0, 3, 1], scale: [0.55, 0.55, 0.55] },
  ground: { rotation: [0, 0, 0], translation: [0, 2, 0], scale: [0.5, 0.5, 0.5] },
  head: { rotation: [0, 180, 0], translation: [0, 13, 7], scale: [1, 1, 1] },
  fixed: { rotation: [0, 180, 0], translation: [0, 0, 0], scale: [1, 1, 1] },
};

class GeometryWriter {
  readonly positions: number[] = [];
  readonly normals: number[] = [];
  readonly uvs: number[] = [];
  readonly indices: number[] = [];

  quad(corners: readonly (readonly [number, number, number])[], uvs: readonly (readonly [number, number])[], normal: readonly [number, number, number]): void {
    const base = this.positions.length / 3;
    for (let index = 0; index < 4; index++) {
      const corner = corners[index] ?? [0, 0, 0];
      const uv = uvs[index] ?? [0, 0];
      this.positions.push(corner[0], corner[1], corner[2]);
      this.normals.push(normal[0], normal[1], normal[2]);
      this.uvs.push(uv[0], uv[1]);
    }
    this.indices.push(base, base + 1, base + 2, base, base + 2, base + 3);
  }

  build(): BufferGeometry {
    const geometry = new BufferGeometry();
    geometry.setAttribute('position', new Float32BufferAttribute(this.positions, 3));
    geometry.setAttribute('normal', new Float32BufferAttribute(this.normals, 3));
    geometry.setAttribute('uv', new Float32BufferAttribute(this.uvs, 2));
    geometry.setIndex(this.indices);
    return geometry;
  }
}

function spriteUv(sprite: AtlasSprite, size: number, u: number, v: number): [number, number] {
  return [(sprite.x + u * sprite.width) / size, (sprite.y + v * sprite.height) / size];
}

function alphaAt(atlas: AtlasView, sprite: AtlasSprite, x: number, y: number): number {
  if (x < 0 || y < 0 || x >= sprite.width || y >= sprite.height) return 0;
  return atlas.pixels[((sprite.y + y) * atlas.size + sprite.x + x) * 4 + 3] ?? 0;
}

export function generatedItemGeometry(atlas: AtlasView, sprites: readonly AtlasSprite[]): BufferGeometry {
  const writer = new GeometryWriter();
  const half = 0.5 / 16;
  sprites.forEach((sprite, layer) => {
    const lift = layer * 0.001;
    writer.quad([[-0.5, 0.5, half + lift], [-0.5, -0.5, half + lift], [0.5, -0.5, half + lift], [0.5, 0.5, half + lift]], [spriteUv(sprite, atlas.size, 0, 0), spriteUv(sprite, atlas.size, 0, 1), spriteUv(sprite, atlas.size, 1, 1), spriteUv(sprite, atlas.size, 1, 0)], [0, 0, 1]);
    writer.quad([[0.5, 0.5, -half - lift], [0.5, -0.5, -half - lift], [-0.5, -0.5, -half - lift], [-0.5, 0.5, -half - lift]], [spriteUv(sprite, atlas.size, 1, 0), spriteUv(sprite, atlas.size, 1, 1), spriteUv(sprite, atlas.size, 0, 1), spriteUv(sprite, atlas.size, 0, 0)], [0, 0, -1]);
    if (layer > 0) return;
    const width = sprite.width;
    const height = sprite.height;
    for (let y = 0; y < height; y++) {
      for (let x = 0; x < width; x++) {
        if (alphaAt(atlas, sprite, x, y) === 0) continue;
        const left = -0.5 + x / width;
        const right = -0.5 + (x + 1) / width;
        const top = 0.5 - y / height;
        const bottom = 0.5 - (y + 1) / height;
        const center = spriteUv(sprite, atlas.size, (x + 0.5) / width, (y + 0.5) / height);
        const uvs = [center, center, center, center];
        if (alphaAt(atlas, sprite, x - 1, y) === 0) writer.quad([[left, top, -half], [left, bottom, -half], [left, bottom, half], [left, top, half]], uvs, [-1, 0, 0]);
        if (alphaAt(atlas, sprite, x + 1, y) === 0) writer.quad([[right, top, half], [right, bottom, half], [right, bottom, -half], [right, top, -half]], uvs, [1, 0, 0]);
        if (alphaAt(atlas, sprite, x, y - 1) === 0) writer.quad([[left, top, -half], [left, top, half], [right, top, half], [right, top, -half]], uvs, [0, 1, 0]);
        if (alphaAt(atlas, sprite, x, y + 1) === 0) writer.quad([[left, bottom, half], [left, bottom, -half], [right, bottom, -half], [right, bottom, half]], uvs, [0, -1, 0]);
      }
    }
  });
  return writer.build();
}

export function bakedGeometry(atlas: AtlasView, quads: readonly BakedQuad[], center: boolean): BufferGeometry {
  const writer = new GeometryWriter();
  const offset = center ? 0.5 : 0;
  for (const quad of quads) {
    const sprite = atlas.sprites.get(quad.sprite) ?? atlas.sprites.get(MISSING_SPRITE);
    if (sprite === undefined) continue;
    const corners: [number, number, number][] = [];
    const uvs: [number, number][] = [];
    for (let vertex = 0; vertex < 4; vertex++) {
      corners.push([(quad.positions[vertex * 3] ?? 0) - offset, (quad.positions[vertex * 3 + 1] ?? 0) - (center ? 0.5 : 0), (quad.positions[vertex * 3 + 2] ?? 0) - offset]);
      uvs.push(spriteUv(sprite, atlas.size, quad.uvs[vertex * 2] ?? 0, quad.uvs[vertex * 2 + 1] ?? 0));
    }
    writer.quad(corners, uvs, DIRECTION_VECTORS[quad.direction]);
  }
  return writer.build();
}

export function applyDisplay(node: Object3D, display: DisplayTransform): void {
  node.position.set(display.translation[0] / 16, display.translation[1] / 16, display.translation[2] / 16);
  node.rotation.set(display.rotation[0] * DEG, display.rotation[1] * DEG, display.rotation[2] * DEG, 'XYZ');
  node.scale.set(display.scale[0], display.scale[1], display.scale[2]);
}

function mirrored(display: DisplayTransform): DisplayTransform {
  return {
    rotation: [display.rotation[0], -display.rotation[1], -display.rotation[2]],
    translation: [-display.translation[0], display.translation[1], display.translation[2]],
    scale: display.scale,
  };
}

export interface ItemMesh {
  readonly object: Group;
  readonly materials: readonly RawShaderMaterial[];
}

export class ItemMeshFactory {
  private readonly pack: ResourcePack;
  private readonly library: BlockLibrary;
  private readonly atlas: AtlasView;
  private readonly uniforms: SharedUniforms;
  private readonly geometries = new Map<string, { geometry: BufferGeometry; model: ResolvedModel } | null>();

  constructor(pack: ResourcePack, library: BlockLibrary, atlas: AtlasView, uniforms: SharedUniforms) {
    this.pack = pack;
    this.library = library;
    this.atlas = atlas;
    this.uniforms = uniforms;
  }

  private geometry(item: string): { geometry: BufferGeometry; model: ResolvedModel } | null {
    if (this.geometries.has(item)) return this.geometries.get(item) ?? null;
    let entry: { geometry: BufferGeometry; model: ResolvedModel } | null = null;
    const modelId = itemModelId(this.pack, item);
    const model = modelId === null ? null : this.library.resolveModel(modelId);
    if (model !== null && modelId !== null) {
      if (model.generatedItem) {
        const sprites: AtlasSprite[] = [];
        for (let layer = 0; layer < 8; layer++) {
          const id = resolveTexture(model, `#layer${String(layer)}`);
          const sprite = id === null ? undefined : this.atlas.sprites.get(id);
          if (sprite !== undefined) sprites.push(sprite);
        }
        if (sprites.length > 0) entry = { geometry: generatedItemGeometry(this.atlas, sprites), model };
      } else {
        const baked = this.library.bakeModelId(modelId);
        if (baked !== null && baked.quads.length > 0) entry = { geometry: bakedGeometry(this.atlas, baked.quads, true), model };
      }
    }
    this.geometries.set(item, entry);
    return entry;
  }

  create(item: string, context: DisplayContext): ItemMesh | null {
    const entry = this.geometry(item);
    if (entry === null) return null;
    const material = createEntityMaterial(this.uniforms, this.atlas.texture, { cutoff: 0.1, doubleSided: false });
    const mesh = new Mesh(entry.geometry, material);
    const display = new Group();
    display.add(mesh);
    const displays = entry.model.display;
    if (context === 'thirdperson_lefthand') {
      applyDisplay(display, mirrored(displays['thirdperson_lefthand'] ?? displays['thirdperson_righthand'] ?? DEFAULT_DISPLAYS.thirdperson_righthand));
    } else {
      applyDisplay(display, displays[context] ?? DEFAULT_DISPLAYS[context]);
    }
    const object = new Group();
    object.add(display);
    return { object, materials: [material] };
  }

  blockMesh(quads: readonly BakedQuad[]): { mesh: Mesh; material: RawShaderMaterial; geometry: BufferGeometry } {
    const geometry = bakedGeometry(this.atlas, quads, false);
    const material = createEntityMaterial(this.uniforms, this.atlas.texture, { cutoff: 0.1 });
    return { mesh: new Mesh(geometry, material), material, geometry };
  }

  dispose(): void {
    for (const entry of this.geometries.values()) entry?.geometry.dispose();
    this.geometries.clear();
  }
}
