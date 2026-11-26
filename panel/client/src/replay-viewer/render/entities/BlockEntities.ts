import { Group, type RawShaderMaterial } from 'three';
import { disposeGeometries } from '../disposeTree.ts';
import type { BlockState } from '../../format/BlockState.ts';
import type { SharedUniforms } from '../WorldMaterials.ts';
import { createEntityMaterial, setEntityLight } from './EntityMaterial.ts';
import type { EntityTextures, LoadedTexture } from './EntityTextures.ts';
import { type ModelDef, buildModel, cube, part } from './ModelParts.ts';
import type { LightProbe } from './EntityRenderer.ts';
import { chestTexture } from './blockEntityStates.ts';

type ChestKind = 'single' | 'left' | 'right';

const FACING_ANGLE: ReadonlyMap<string, number> = new Map([['south', 0], ['west', 90], ['north', 180], ['east', 270]]);

function chestModel(kind: ChestKind): ModelDef {
  if (kind === 'left') {
    return { parts: [
      part('bottom', [0, 0, 0], [cube(0, 19, [1, 0, 1], [15, 10, 14])]),
      part('lid', [0, 9, 1], [cube(0, 0, [1, 0, 0], [15, 5, 14])]),
      part('lock', [0, 8, 0], [cube(0, 0, [15, -2, 14], [1, 4, 1])]),
    ] };
  }
  if (kind === 'right') {
    return { parts: [
      part('bottom', [0, 0, 0], [cube(0, 19, [0, 0, 1], [15, 10, 14])]),
      part('lid', [0, 9, 1], [cube(0, 0, [0, 0, 0], [15, 5, 14])]),
      part('lock', [0, 8, 0], [cube(0, 0, [0, -2, 14], [1, 4, 1])]),
    ] };
  }
  return { parts: [
    part('bottom', [0, 0, 0], [cube(0, 19, [1, 0, 1], [14, 10, 14])]),
    part('lid', [0, 9, 1], [cube(0, 0, [1, 0, 0], [14, 5, 14])]),
    part('lock', [0, 8, 0], [cube(0, 0, [7, -2, 14], [2, 4, 1])]),
  ] };
}

function chestKind(state: BlockState): ChestKind {
  const type = state.properties['type'];
  return type === 'left' || type === 'right' ? type : 'single';
}

interface Placed {
  readonly key: string;
  readonly object: Group;
  readonly material: RawShaderMaterial;
}

export class BlockEntities {
  readonly group = new Group();
  private readonly textures: EntityTextures;
  private readonly uniforms: SharedUniforms;
  private readonly probe: LightProbe;
  private readonly placed = new Map<string, Placed>();
  private readonly wanted = new Map<string, string>();
  onChange: () => void = () => undefined;

  constructor(textures: EntityTextures, uniforms: SharedUniforms, probe: LightProbe) {
    this.textures = textures;
    this.uniforms = uniforms;
    this.probe = probe;
  }

  set(x: number, y: number, z: number, state: BlockState | null): void {
    const position = `${String(x)},${String(y)},${String(z)}`;
    const existing = this.placed.get(position);
    const base = state === null ? null : chestTexture(state);
    const key = state !== null && base !== null ? `${state.name}|${chestKind(state)}|${state.properties['facing'] ?? 'north'}` : '';
    if (base !== null) this.wanted.set(position, key);
    else this.wanted.delete(position);
    if (existing?.key === key) return;
    if (existing !== undefined) this.remove(position, existing);
    if (state === null || base === null) return;
    const kind = chestKind(state);
    const legacy = kind === 'single' ? [] : [`textures/entity/chest/${base}_double.png`];
    void this.textures.first([`textures/entity/chest/${base}${kind === 'single' ? '' : `_${kind}`}.png`, ...legacy]).then((texture) => {
      if (texture === null || this.wanted.get(position) !== key || this.placed.has(position)) return;
      if (kind !== 'single' && texture.width !== 64) return;
      this.place(position, key, x, y, z, state, kind, texture);
      this.onChange();
    }, () => undefined);
  }

  private place(position: string, key: string, x: number, y: number, z: number, state: BlockState, kind: ChestKind, texture: LoadedTexture): void {
    const material = createEntityMaterial(this.uniforms, texture.texture);
    const model = buildModel(chestModel(kind), material, texture.width, texture.height);
    const unflip = new Group();
    unflip.rotation.x = Math.PI;
    unflip.position.y = 1.5;
    unflip.add(model.root);
    const centered = new Group();
    centered.position.set(-0.5, -0.5, -0.5);
    centered.add(unflip);
    const object = new Group();
    object.position.set(x + 0.5, y + 0.5, z + 0.5);
    object.rotation.y = -(FACING_ANGLE.get(state.properties['facing'] ?? 'south') ?? 0) * Math.PI / 180;
    object.add(centered);
    const light = this.probe(x + 0.5, y + 0.5, z + 0.5);
    setEntityLight(material, light >> 4, light & 15);
    this.group.add(object);
    this.placed.set(position, { key, object, material });
  }

  private remove(position: string, placed: Placed): void {
    placed.object.removeFromParent();
    placed.material.dispose();
    disposeGeometries(placed.object);
    this.placed.delete(position);
  }

  clear(): void {
    this.wanted.clear();
    for (const [position, placed] of [...this.placed]) this.remove(position, placed);
  }

  dispose(): void {
    this.clear();
  }
}
