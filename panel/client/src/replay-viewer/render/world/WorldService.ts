import type { ResourcePack } from '../../assets/ResourcePack.ts';
import type { AtlasLayout, AtlasSprite } from './Atlas.ts';
import { BiomeTable, type Biome, parseBiome } from './Biomes.ts';
import { type BlockLibrary, MISSING_SPRITE, UNKNOWN_SPRITE } from './BlockLibrary.ts';
import { BlockRenders } from './BlockRenders.ts';
import type { BlockState } from '../../format/BlockState.ts';
import type { WorldDimension } from '../../format/ReplayModel.ts';
import { StatePalette } from '../../format/StatePalette.ts';
import { SECTION_VOLUME, blockOrigin, sectionKey, unpackSectionKey } from '../../format/WorldLayout.ts';
import { BlockWorld, type SectionData } from './BlockWorld.ts';
import { FLUID_SPRITES } from './FluidMesher.ts';
import { itemModelId } from './ItemModels.ts';
import { deriveLegacyState, needsLegacyDerivation } from './LegacyConnections.ts';
import { resolveTexture } from './ModelResolver.ts';
import { type SectionMesh, SectionMesher } from './SectionMesher.ts';
import type { Colormaps } from './Tints.ts';

export interface WorldSection {
  readonly x: number;
  readonly y: number;
  readonly z: number;
  readonly data: SectionData;
}


export interface BlockChange {
  readonly x: number;
  readonly y: number;
  readonly z: number;
  readonly state: number;
}

export interface AssetSetup {
  readonly pack: ResourcePack;
  readonly palette: readonly BlockState[];
  readonly biomeKeys: readonly string[];
  readonly itemKeys: readonly string[];
}

export function blockSource(pack: ResourcePack): ConstructorParameters<typeof BlockLibrary>[0] {
  return { blockstate: (name) => pack.blockstate(name), model: (id) => pack.model(id) };
}

export function loadBiomes(pack: ResourcePack): BiomeTable {
  const loaded = new Map<string, Biome>();
  for (const path of pack.paths('biome/')) {
    const biome = parseBiome(pack.json(path));
    if (biome !== null) loaded.set(path.slice('biome/'.length, -'.json'.length), biome);
  }
  return new BiomeTable(loaded);
}

export function collectSprites(setup: AssetSetup, library: BlockLibrary): Set<string> {
  const sprites = new Set<string>([MISSING_SPRITE, UNKNOWN_SPRITE]);
  for (const [still, flow] of Object.values(FLUID_SPRITES)) {
    sprites.add(still);
    sprites.add(flow);
  }
  for (const state of setup.palette) {
    for (const options of library.bake(state).parts) {
      for (const option of options) {
        for (const quad of option.model.quads) sprites.add(quad.sprite);
      }
    }
    if (setup.pack.legacy && needsLegacyDerivation(state.name)) {
      for (const model of library.allModels(state.name)) for (const quad of model.quads) sprites.add(quad.sprite);
    }
  }
  for (const item of setup.itemKeys) {
    const modelId = itemModelId(setup.pack, item);
    const model = modelId === null ? null : library.resolveModel(modelId);
    if (model === null) continue;
    if (model.generatedItem) {
      for (let layer = 0; layer < 8; layer++) {
        const sprite = resolveTexture(model, `#layer${String(layer)}`);
        if (sprite !== null) sprites.add(sprite);
      }
    }
    for (const element of model.elements) {
      for (const face of Object.values(element.faces)) {
        const sprite = resolveTexture(model, face.texture);
        if (sprite !== null) sprites.add(sprite);
      }
    }
  }
  return sprites;
}

export class WorldAssets {
  readonly palette: StatePalette;
  readonly renders: BlockRenders;
  readonly biomes: BiomeTable;
  readonly biomeKeys: readonly string[];
  readonly atlas: AtlasLayout;
  readonly colormaps: Colormaps;
  readonly legacy: boolean;
  readonly sprite: (id: string) => AtlasSprite | null;

  constructor(setup: AssetSetup, library: BlockLibrary, atlas: AtlasLayout, colormaps: Colormaps) {
    this.legacy = setup.pack.legacy;
    this.palette = new StatePalette(setup.palette);
    this.atlas = atlas;
    this.colormaps = colormaps;
    this.biomeKeys = setup.biomeKeys;
    this.biomes = loadBiomes(setup.pack);
    this.sprite = (id) => atlas.sprites.get(id) ?? null;
    this.renders = new BlockRenders(library, this.palette.states, this.sprite);
  }
}

export class WorldService {
  readonly world: BlockWorld;
  private readonly palette: StatePalette;
  private readonly mesher: SectionMesher;
  private readonly renders: BlockRenders;
  private readonly legacy: boolean;

  constructor(assets: WorldAssets, dimension: WorldDimension, sections: readonly WorldSection[]) {
    this.legacy = assets.legacy;
    this.palette = assets.palette;
    this.renders = assets.renders;
    this.world = new BlockWorld({ hasSkyLight: dimension.hasSkyLight });
    for (const section of sections) this.world.putSection(section.x, section.y, section.z, section.data);
    this.mesher = new SectionMesher({
      world: this.world,
      renders: this.renders,
      biomes: assets.biomes,
      biomeKeys: assets.biomeKeys,
      colormaps: assets.colormaps,
      atlasSize: assets.atlas.size,
      sprite: assets.sprite,
      legacyWater: this.legacy,
      netherShading: dimension.key.endsWith('the_nether'),
    });
    if (this.legacy) this.deriveAll();
  }

  mesh(key: number): SectionMesh {
    const [x, y, z] = unpackSectionKey(key);
    return this.mesher.mesh(x, y, z);
  }

  sectionKeys(): number[] {
    return this.world.sectionKeys();
  }

  apply(changes: readonly BlockChange[]): Set<number> {
    const dirty = new Set<number>();
    for (const change of changes) {
      if (!this.world.setState(change.x, change.y, change.z, change.state)) continue;
      this.markDirty(dirty, change.x, change.y, change.z);
      if (this.legacy) {
        for (let dy = -1; dy <= 1; dy++) {
          for (let dz = -1; dz <= 1; dz++) {
            for (let dx = -1; dx <= 1; dx++) {
              if (this.deriveAt(change.x + dx, change.y + dy, change.z + dz)) this.markDirty(dirty, change.x + dx, change.y + dy, change.z + dz);
            }
          }
        }
      }
    }
    return dirty;
  }

  private markDirty(dirty: Set<number>, x: number, y: number, z: number): void {
    for (let dy = -1; dy <= 1; dy++) {
      for (let dz = -1; dz <= 1; dz++) {
        for (let dx = -1; dx <= 1; dx++) {
          const sectionX = (x + dx) >> 4;
          const sectionY = (y + dy) >> 4;
          const sectionZ = (z + dz) >> 4;
          if (this.world.section(sectionX, sectionY, sectionZ) === undefined) continue;
          dirty.add(sectionKey(sectionX, sectionY, sectionZ));
        }
      }
    }
  }

  private deriveAll(): void {
    for (const key of this.world.sectionKeys()) {
      const [sectionX, sectionY, sectionZ] = unpackSectionKey(key);
      const states = this.world.section(sectionX, sectionY, sectionZ)?.states ?? null;
      if (states === null) continue;
      for (let index = 0; index < SECTION_VOLUME; index++) {
        const state = this.palette.get(states[index] ?? 0);
        if (state === null || !needsLegacyDerivation(state.name)) continue;
        this.deriveAt(...blockOrigin(sectionX, sectionY, sectionZ, index));
      }
    }
  }

  private deriveAt(x: number, y: number, z: number): boolean {
    const state = this.palette.get(this.world.state(x, y, z));
    if (state === null || !needsLegacyDerivation(state.name)) return false;
    const derived = deriveLegacyState(
      state,
      (dx, dy, dz) => this.palette.get(this.world.state(x + dx, y + dy, z + dz)),
      (other) => this.isSolid(other),
    );
    if (derived === null) return false;
    return this.world.setState(x, y, z, this.palette.intern(derived));
  }

  private isSolid(state: BlockState): boolean {
    const index = this.palette.intern(state);
    return this.renders.get(index).opaque;
  }
}
