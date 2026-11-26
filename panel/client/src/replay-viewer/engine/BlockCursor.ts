import type { BlockChange } from '../render/world/WorldService.ts';
import type { BlockChanges } from './ReplayTimeline.ts';

export class BlockCursor {
  private readonly changes: BlockChanges;
  private readonly world: number;
  private applied = 0;

  constructor(changes: BlockChanges, world: number) {
    this.changes = changes;
    this.world = world;
  }

  moveTo(time: number): BlockChange[] {
    const result: BlockChange[] = [];
    while (this.applied < this.changes.size && this.changes.timeAt(this.applied) <= time) {
      const change = this.changes.at(this.applied);
      if (change?.world === this.world) result.push({ x: change.x, y: change.y, z: change.z, state: change.state });
      this.applied += 1;
    }
    while (this.applied > 0 && this.changes.timeAt(this.applied - 1) > time) {
      const change = this.changes.at(this.applied - 1);
      if (change?.world === this.world) result.push({ x: change.x, y: change.y, z: change.z, state: change.previous });
      this.applied -= 1;
    }
    return result;
  }
}
