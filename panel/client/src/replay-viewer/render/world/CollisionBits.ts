import { SECTION_VOLUME, type SectionStates } from '../../format/WorldLayout.ts';

export const SECTION_BIT_BYTES = SECTION_VOLUME / 8;

export function collisionBit(bits: Uint8Array, offset: number, index: number): boolean {
  return ((bits[offset + (index >> 3)] ?? 0) & (1 << (index & 7))) !== 0;
}

export function packCollision(states: SectionStates, fullCube: (state: number) => boolean, bits: Uint8Array, offset: number): boolean {
  let any = false;
  for (let index = 0; index < SECTION_VOLUME; index++) {
    const state = states[index] ?? 0;
    if (state === 0 || !fullCube(state)) continue;
    const byte = offset + (index >> 3);
    bits[byte] = (bits[byte] ?? 0) | (1 << (index & 7));
    any = true;
  }
  return any;
}
