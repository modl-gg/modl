import { type BlockState, stateKey } from './BlockState.ts';

export class StatePalette {
  readonly states: BlockState[];
  private readonly indices = new Map<string, number>();

  constructor(initial: readonly BlockState[]) {
    this.states = [...initial];
    this.states.forEach((state, index) => {
      const key = stateKey(state);
      if (!this.indices.has(key)) this.indices.set(key, index);
    });
  }

  intern(state: BlockState): number {
    const key = stateKey(state);
    const existing = this.indices.get(key);
    if (existing !== undefined) return existing;
    const index = this.states.length;
    this.states.push(state);
    this.indices.set(key, index);
    return index;
  }

  get(index: number): BlockState | null {
    return this.states[index] ?? null;
  }
}
