import type { BlockState } from '../../format/BlockState.ts';
import type { BlockLibrary } from './BlockLibrary.ts';
import { isFullCube } from './BlockRenders.ts';

export class FullCubes {
  private readonly library: BlockLibrary;
  private readonly palette: readonly BlockState[];
  private readonly known = new Map<number, boolean>();

  constructor(library: BlockLibrary, palette: readonly BlockState[]) {
    this.library = library;
    this.palette = palette;
  }

  has(state: number): boolean {
    let full = this.known.get(state);
    if (full === undefined) {
      const blockState = this.palette[state];
      full = blockState !== undefined && isFullCube(this.library.bake(blockState));
      this.known.set(state, full);
    }
    return full;
  }
}
