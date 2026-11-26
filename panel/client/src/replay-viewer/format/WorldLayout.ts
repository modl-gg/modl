import { MAX_UNCOMPRESSED_BYTES, SECTION_STATES } from './ReplayLimits.ts';
import { ReplayFormatError } from './ReplayModel.ts';

const XZ_BITS = 2 ** 22;
const Y_BITS = 2 ** 9;
const OFFSET_XZ = XZ_BITS / 2;
const OFFSET_Y = Y_BITS / 2;

export const SECTION_VOLUME = SECTION_STATES;
export const LIGHT_BYTES = SECTION_VOLUME / 2;
export const BIOME_CELLS = 64;
const MAX_COMPACT_STATE = 0xffff;

export type SectionStates = Uint16Array<ArrayBuffer> | Uint32Array<ArrayBuffer>;
export const MAX_SECTION_XZ = OFFSET_XZ - 1;
export const MAX_SECTION_Y = OFFSET_Y - 1;

export function sectionInRange(sectionX: number, sectionY: number, sectionZ: number): boolean {
  return Number.isInteger(sectionX) && Number.isInteger(sectionY) && Number.isInteger(sectionZ)
    && Math.abs(sectionX) <= MAX_SECTION_XZ && Math.abs(sectionZ) <= MAX_SECTION_XZ && Math.abs(sectionY) <= MAX_SECTION_Y;
}

export function blockInRange(x: number, y: number, z: number): boolean {
  return sectionInRange(x >> 4, y >> 4, z >> 4);
}

export function sectionKey(sectionX: number, sectionY: number, sectionZ: number): number {
  return ((sectionX + OFFSET_XZ) * XZ_BITS + (sectionZ + OFFSET_XZ)) * Y_BITS + (sectionY + OFFSET_Y);
}

export function unpackSectionKey(key: number): [number, number, number] {
  const sectionY = (key % Y_BITS) - OFFSET_Y;
  const rest = Math.floor(key / Y_BITS);
  const sectionZ = (rest % XZ_BITS) - OFFSET_XZ;
  const sectionX = Math.floor(rest / XZ_BITS) - OFFSET_XZ;
  return [sectionX, sectionY, sectionZ];
}

export function blockIndex(localX: number, localY: number, localZ: number): number {
  return (localY << 8) | (localZ << 4) | localX;
}

export function blockSectionKey(x: number, y: number, z: number): number {
  return sectionKey(x >> 4, y >> 4, z >> 4);
}

export function localIndex(x: number, y: number, z: number): number {
  return blockIndex(x & 15, y & 15, z & 15);
}

export function biomeCell(x: number, y: number, z: number): number {
  return ((((y & 15) >> 2) * 4 + ((z & 15) >> 2)) * 4) + ((x & 15) >> 2);
}

export function nibble(array: Uint8Array, index: number): number {
  const byte = array[index >> 1] ?? 0;
  return (index & 1) === 0 ? byte & 0x0f : byte >> 4;
}

export function createStates(largestState: number): SectionStates {
  return largestState > MAX_COMPACT_STATE ? new Uint32Array(SECTION_VOLUME) : new Uint16Array(SECTION_VOLUME);
}

export function statesHolding(states: SectionStates | null, state: number): SectionStates {
  if (states === null) return createStates(state);
  if (state <= MAX_COMPACT_STATE || states instanceof Uint32Array) return states;
  return Uint32Array.from(states);
}

export function requireSection(x: number, y: number, z: number): void {
  if (!sectionInRange(x, y, z)) throw new ReplayFormatError('section outside the world');
}

export function requireBlock(x: number, y: number, z: number): void {
  if (!blockInRange(x, y, z)) throw new ReplayFormatError('block outside the world');
}

export class SectionBudget {
  private spent = 0;

  charge(bytes: number): void {
    this.spent += bytes;
    if (this.spent > MAX_UNCOMPRESSED_BYTES) throw new ReplayFormatError('world sections too large');
  }
}

export type BlockVisitor<T> = (x: number, y: number, z: number, value: T) => void;

export function blockOrigin(sectionX: number, sectionY: number, sectionZ: number, index: number): [number, number, number] {
  return [sectionX * 16 + (index & 15), sectionY * 16 + (index >> 8), sectionZ * 16 + ((index >> 4) & 15)];
}

export class SparseBlockMap<T> {
  private readonly sections = new Map<number, Map<number, T>>();

  get(x: number, y: number, z: number): T | undefined {
    return this.sections.get(blockSectionKey(x, y, z))?.get(localIndex(x, y, z));
  }

  set(x: number, y: number, z: number, value: T): void {
    const key = blockSectionKey(x, y, z);
    let cells = this.sections.get(key);
    if (cells === undefined) {
      cells = new Map();
      this.sections.set(key, cells);
    }
    cells.set(localIndex(x, y, z), value);
  }

  delete(x: number, y: number, z: number): void {
    const key = blockSectionKey(x, y, z);
    const cells = this.sections.get(key);
    if (cells === undefined) return;
    cells.delete(localIndex(x, y, z));
    if (cells.size === 0) this.sections.delete(key);
  }

  forEach(visit: BlockVisitor<T>): void {
    for (const [key, cells] of this.sections) {
      const [sectionX, sectionY, sectionZ] = unpackSectionKey(key);
      for (const [index, value] of cells) {
        const [x, y, z] = blockOrigin(sectionX, sectionY, sectionZ, index);
        visit(x, y, z, value);
      }
    }
  }
}
