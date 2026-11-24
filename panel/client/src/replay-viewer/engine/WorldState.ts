import { type BlockSnapshot, EventType, type BlockChangeEvent, type ParsedReplay } from '../types/ReplayFile';

/**
 * Manages world block state, supporting forward/backward seeking.
 * Entity/player state is handled separately by ReplayEngine.
 */
export class WorldState {
  /** Initial snapshot blocks, keyed by "x,y,z" */
  private readonly initialBlocks: Map<string, BlockSnapshot>;
  /** Current block states, keyed by "x,y,z" */
  private currentBlocks: Map<string, BlockSnapshot>;
  /** Block change events sorted by timestamp */
  private readonly blockChanges: BlockChangeEvent[];
  /** Current index into blockChanges (events up to this index have been applied) */
  private currentIndex = 0;

  constructor(replay: ParsedReplay) {
    this.initialBlocks = new Map();
    this.currentBlocks = new Map();

    for (const block of replay.snapshot) {
      const key = `${block.x},${block.y},${block.z}`;
      this.initialBlocks.set(key, block);
      this.currentBlocks.set(key, block);
    }

    this.blockChanges = replay.events.filter(
      (e): e is BlockChangeEvent => e.type === EventType.BLOCK_CHANGE
    );
  }

  /**
   * Advance or rewind world state to the given timestamp.
   * Returns the list of blocks that changed since the last call.
   */
  advanceTo(timeMs: number): BlockChange[] {
    const changes: BlockChange[] = [];

    if (this.needsReset(timeMs)) {
      // Backward seek or large jump - reset to initial snapshot and replay
      return this.resetAndReplayTo(timeMs);
    }

    // Forward seek - apply events from currentIndex to target
    let event = this.blockChanges[this.currentIndex];
    while (event !== undefined && event.timestampDeltaMs <= timeMs) {
      const change: BlockChange = { x: event.x, y: event.y, z: event.z, stateId: event.stateId };
      this.currentBlocks.set(`${event.x},${event.y},${event.z}`, change);
      changes.push(change);
      this.currentIndex++;
      event = this.blockChanges[this.currentIndex];
    }

    return changes;
  }

  private needsReset(targetTimeMs: number): boolean {
    const lastApplied = this.blockChanges[this.currentIndex - 1];
    return lastApplied !== undefined && targetTimeMs < lastApplied.timestampDeltaMs;
  }

  private resetAndReplayTo(timeMs: number): BlockChange[] {
    // Reset to initial snapshot
    this.currentBlocks = new Map(this.initialBlocks);
    this.currentIndex = 0;

    // Replay all block changes up to timeMs
    return this.advanceTo(timeMs);
  }

  /** Get the current state ID of a block, or undefined if not in snapshot */
  getBlockState(x: number, y: number, z: number): number | undefined {
    return this.currentBlocks.get(`${x},${y},${z}`)?.stateId;
  }

  /** Get all current blocks (for initial rendering) */
  getAllBlocks(): BlockSnapshot[] {
    return Array.from(this.currentBlocks.values());
  }
}

export interface BlockChange {
  readonly x: number;
  readonly y: number;
  readonly z: number;
  readonly stateId: number;
}
