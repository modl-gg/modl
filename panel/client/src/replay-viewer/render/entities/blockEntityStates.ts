import type { BlockState } from '../../format/BlockState.ts';

const CHEST_TEXTURES: ReadonlyMap<string, string> = new Map([['chest', 'normal'], ['trapped_chest', 'trapped'], ['ender_chest', 'ender']]);

export function chestTexture(state: BlockState): string | null {
  return CHEST_TEXTURES.get(state.name) ?? null;
}

export function isBlockEntityState(state: BlockState | null | undefined): boolean {
  return state !== null && state !== undefined && chestTexture(state) !== null;
}
