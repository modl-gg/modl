import {
  type SectionStates,
  biomeCell,
  blockInRange,
  blockSectionKey,
  localIndex,
  nibble,
  sectionInRange,
  sectionKey,
  statesHolding,
} from '../../format/WorldLayout.ts';

export interface SectionData {
  readonly states: SectionStates | null;
  readonly skyLight: Uint8Array | null;
  readonly blockLight: Uint8Array | null;
  readonly biomes: Uint32Array | null;
}

interface StoredSection {
  states: SectionStates | null;
  readonly skyLight: Uint8Array | null;
  readonly blockLight: Uint8Array | null;
  readonly biomes: Uint32Array | null;
}

export interface WorldOptions {
  readonly hasSkyLight: boolean;
}

export class BlockWorld {
  readonly options: WorldOptions;
  private readonly sections = new Map<number, StoredSection>();
  private lastKey = Number.NaN;
  private lastSection: StoredSection | undefined = undefined;

  constructor(options: WorldOptions) {
    this.options = options;
  }

  putSection(sectionX: number, sectionY: number, sectionZ: number, data: SectionData): void {
    if (!sectionInRange(sectionX, sectionY, sectionZ)) return;
    this.sections.set(sectionKey(sectionX, sectionY, sectionZ), { ...data });
    this.lastKey = Number.NaN;
  }

  section(sectionX: number, sectionY: number, sectionZ: number): SectionData | undefined {
    return this.stored(sectionKey(sectionX, sectionY, sectionZ));
  }

  private stored(key: number): StoredSection | undefined {
    if (key === this.lastKey) return this.lastSection;
    const found = this.sections.get(key);
    this.lastKey = key;
    this.lastSection = found;
    return found;
  }

  sectionKeys(): number[] {
    return [...this.sections.keys()];
  }

  state(x: number, y: number, z: number): number {
    const states = this.stored(blockSectionKey(x, y, z))?.states ?? null;
    return states === null ? 0 : states[localIndex(x, y, z)] ?? 0;
  }

  setState(x: number, y: number, z: number, state: number): boolean {
    if (!blockInRange(x, y, z)) return false;
    const key = blockSectionKey(x, y, z);
    let section = this.stored(key);
    if (section === undefined) {
      if (state === 0) return false;
      section = { states: null, skyLight: null, blockLight: null, biomes: null };
      this.sections.set(key, section);
      this.lastKey = Number.NaN;
    }
    const index = localIndex(x, y, z);
    if ((section.states?.[index] ?? 0) === state) return false;
    const states = statesHolding(section.states, state);
    states[index] = state;
    section.states = states;
    return true;
  }

  light(x: number, y: number, z: number): number {
    const section = this.stored(blockSectionKey(x, y, z));
    const defaultSky = this.options.hasSkyLight ? 15 : 0;
    if (section === undefined) return defaultSky << 4;
    const index = localIndex(x, y, z);
    const sky = section.skyLight === null ? defaultSky : nibble(section.skyLight, index);
    const block = section.blockLight === null ? 0 : nibble(section.blockLight, index);
    return (sky << 4) | block;
  }

  biome(x: number, y: number, z: number): number {
    const biomes = this.stored(blockSectionKey(x, y, z))?.biomes ?? null;
    return biomes === null ? -1 : biomes[biomeCell(x, y, z)] ?? -1;
  }
}
