import type { ReplaySection } from '../format/ReplayModel.ts';
import { SparseBlockMap, biomeCell, blockSectionKey, localIndex, nibble, sectionKey } from '../format/WorldLayout.ts';
import { SECTION_BIT_BYTES, collisionBit } from '../render/world/CollisionBits.ts';

interface ProbeSection {
  readonly skyLight: Uint8Array | null;
  readonly blockLight: Uint8Array | null;
  readonly biomes: Uint32Array | null;
}

interface CollisionSection {
  readonly bits: Uint8Array;
  readonly offset: number;
}

export class WorldProbe {
  private readonly defaultSky: number;
  private readonly sections = new Map<number, ProbeSection>();
  private readonly collision = new Map<number, CollisionSection>();
  private readonly overrides = new SparseBlockMap<boolean>();

  constructor(hasSkyLight: boolean, sections: readonly ReplaySection[]) {
    this.defaultSky = hasSkyLight ? 15 : 0;
    for (const section of sections) {
      if (section.skyLight === null && section.blockLight === null && section.biomes === null) continue;
      this.sections.set(sectionKey(section.x, section.y, section.z), { skyLight: section.skyLight, blockLight: section.blockLight, biomes: section.biomes });
    }
  }

  acceptCollision(keys: Float64Array, bits: Uint8Array): void {
    keys.forEach((key, index) => {
      this.collision.set(key, { bits, offset: index * SECTION_BIT_BYTES });
    });
  }

  setSolid(x: number, y: number, z: number, solid: boolean): void {
    this.overrides.set(x, y, z, solid);
  }

  solid(x: number, y: number, z: number): boolean {
    const override = this.overrides.get(x, y, z);
    if (override !== undefined) return override;
    const section = this.collision.get(blockSectionKey(x, y, z));
    return section !== undefined && collisionBit(section.bits, section.offset, localIndex(x, y, z));
  }

  light(x: number, y: number, z: number): number {
    const section = this.sections.get(blockSectionKey(x, y, z));
    if (section === undefined) return this.defaultSky << 4;
    const index = localIndex(x, y, z);
    const sky = section.skyLight === null ? this.defaultSky : nibble(section.skyLight, index);
    const block = section.blockLight === null ? 0 : nibble(section.blockLight, index);
    return (sky << 4) | block;
  }

  biome(x: number, y: number, z: number): number {
    const biomes = this.sections.get(blockSectionKey(x, y, z))?.biomes ?? null;
    return biomes === null ? -1 : biomes[biomeCell(x, y, z)] ?? -1;
  }
}
