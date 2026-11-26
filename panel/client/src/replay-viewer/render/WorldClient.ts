import type { AtlasSprite } from './world/Atlas.ts';
import type { BlockState } from '../format/BlockState.ts';
import type { SectionMesh } from './world/SectionMesher.ts';
import type { BlockChange } from './world/WorldService.ts';
import { type ChangesMessage, type FocusMessage, type InitMessage, type WireWorld, type WorldMessage, parseWorldResponse, sectionTransfers } from './world/worldProtocol.ts';

export interface WorldClientListener {
  atlas(size: number, levels: readonly Uint8Array<ArrayBuffer>[], sprites: readonly AtlasSprite[]): void;
  section(key: number, x: number, y: number, z: number, mesh: SectionMesh): void;
  idle(): void;
  collision(world: number, keys: Float64Array, bits: Uint8Array): void;
  error(message: string): void;
}

export interface WorldAssetsInit {
  readonly version: string;
  readonly primary: Map<string, Uint8Array<ArrayBuffer>>;
  readonly models: Map<string, Uint8Array<ArrayBuffer>> | null;
  readonly palette: readonly BlockState[];
  readonly biomeKeys: readonly string[];
  readonly itemKeys: readonly string[];
  readonly worlds: readonly WireWorld[];
}

export class WorldClient {
  private readonly worker: Worker;
  private generation = 0;

  constructor(init: WorldAssetsInit, listener: WorldClientListener) {
    this.worker = new Worker(new URL('./world/world.worker.ts', import.meta.url), { type: 'module', name: 'modl-replay-world' });
    this.worker.addEventListener('message', (event: MessageEvent<unknown>) => {
      const message = parseWorldResponse(event.data);
      if (message === null) return;
      switch (message.type) {
        case 'atlas':
          listener.atlas(message.size, message.levels, message.sprites);
          return;
        case 'section':
          if (message.generation === this.generation) listener.section(message.key, message.x, message.y, message.z, message.mesh);
          return;
        case 'idle':
          if (message.generation === this.generation) listener.idle();
          return;
        case 'collision':
          listener.collision(message.world, message.keys, message.bits);
          return;
        case 'error':
          listener.error(message.message);
          return;
      }
    });
    this.worker.addEventListener('error', () => {
      listener.error('world worker failed');
    });
    const message: InitMessage = { type: 'init', ...init };
    this.worker.postMessage(message, sectionTransfers(init.worlds));
  }

  openWorld(world: number, changes: readonly BlockChange[], focus: readonly [number, number, number]): void {
    this.generation += 1;
    const message: WorldMessage = { type: 'world', generation: this.generation, world, changes, focus };
    this.worker.postMessage(message);
  }

  apply(changes: readonly BlockChange[]): void {
    if (changes.length === 0) return;
    const message: ChangesMessage = { type: 'changes', generation: this.generation, changes };
    this.worker.postMessage(message);
  }

  focus(x: number, y: number, z: number): void {
    const message: FocusMessage = { type: 'focus', focus: [x, y, z] };
    this.worker.postMessage(message);
  }

  dispose(): void {
    this.worker.terminate();
  }
}
