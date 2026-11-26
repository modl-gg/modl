import { isRecord } from '../../assets/json.ts';
import type { AlphaClass, AtlasSprite, SpriteAnimation } from './Atlas.ts';
import { isBlockState, isSection, isStringList, isWorldDimension } from '../../format/guards.ts';
import type { BlockState } from '../../format/BlockState.ts';
import type { ReplaySection, WorldDimension } from '../../format/ReplayModel.ts';
import { SECTION_BIT_BYTES } from './CollisionBits.ts';
import type { LayerMesh } from './MeshBuilder.ts';
import type { SectionMesh } from './SectionMesher.ts';
import type { BlockChange } from './WorldService.ts';

export interface WireWorld {
  readonly world: number;
  readonly dimension: WorldDimension;
  readonly sections: readonly ReplaySection[];
}

export interface InitMessage {
  readonly type: 'init';
  readonly version: string;
  readonly primary: Map<string, Uint8Array<ArrayBuffer>>;
  readonly models: Map<string, Uint8Array<ArrayBuffer>> | null;
  readonly palette: readonly BlockState[];
  readonly biomeKeys: readonly string[];
  readonly itemKeys: readonly string[];
  readonly worlds: readonly WireWorld[];
}

export interface WorldMessage {
  readonly type: 'world';
  readonly generation: number;
  readonly world: number;
  readonly changes: readonly BlockChange[];
  readonly focus: readonly [number, number, number];
}

export interface ChangesMessage {
  readonly type: 'changes';
  readonly generation: number;
  readonly changes: readonly BlockChange[];
}

export interface FocusMessage {
  readonly type: 'focus';
  readonly focus: readonly [number, number, number];
}

export type WorldRequest = InitMessage | WorldMessage | ChangesMessage | FocusMessage;

export interface AtlasMessage {
  readonly type: 'atlas';
  readonly size: number;
  readonly levels: readonly Uint8Array<ArrayBuffer>[];
  readonly sprites: readonly AtlasSprite[];
}

export interface SectionMessage {
  readonly type: 'section';
  readonly generation: number;
  readonly key: number;
  readonly x: number;
  readonly y: number;
  readonly z: number;
  readonly mesh: SectionMesh;
}

export interface IdleMessage {
  readonly type: 'idle';
  readonly generation: number;
}

export interface CollisionMessage {
  readonly type: 'collision';
  readonly world: number;
  readonly keys: Float64Array;
  readonly bits: Uint8Array;
}

export interface WorldErrorMessage {
  readonly type: 'error';
  readonly message: string;
}

export type WorldResponse = AtlasMessage | SectionMessage | IdleMessage | CollisionMessage | WorldErrorMessage;

function isNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

function isLayer(value: unknown): value is LayerMesh {
  return isRecord(value)
    && value['positions'] instanceof Float32Array
    && value['uvs'] instanceof Float32Array
    && value['colors'] instanceof Uint8Array
    && value['lights'] instanceof Uint8Array
    && value['indices'] instanceof Uint32Array
    && isNumber(value['quadCount']);
}

function isAlpha(value: unknown): value is AlphaClass {
  return value === 'solid' || value === 'cutout' || value === 'translucent';
}

function isAnimation(value: unknown): value is SpriteAnimation | null {
  if (value === null) return true;
  return isRecord(value) && Array.isArray(value['frames']) && typeof value['interpolate'] === 'boolean' && isNumber(value['totalTicks'])
    && value['frames'].every((frame) => isRecord(frame) && isNumber(frame['index']) && isNumber(frame['ticks']));
}

function isSprite(value: unknown): value is AtlasSprite {
  return isRecord(value) && typeof value['id'] === 'string' && isNumber(value['x']) && isNumber(value['y'])
    && isNumber(value['width']) && isNumber(value['height']) && isNumber(value['animationSlot']) && isAlpha(value['alpha'])
    && isAnimation(value['animation'])
    && Array.isArray(value['frameOrigins']) && value['frameOrigins'].every((origin) => Array.isArray(origin) && origin.length === 2 && origin.every(isNumber));
}

export function parseWorldResponse(value: unknown): WorldResponse | null {
  if (!isRecord(value)) return null;
  switch (value['type']) {
    case 'atlas': {
      const levels = value['levels'];
      const sprites = value['sprites'];
      const size = value['size'];
      if (!isNumber(size) || !Array.isArray(levels) || !Array.isArray(sprites)) return null;
      if (!levels.every((level) => level instanceof Uint8Array) || !sprites.every(isSprite)) return null;
      return { type: 'atlas', size, levels: levels.filter((level): level is Uint8Array<ArrayBuffer> => level instanceof Uint8Array), sprites };
    }
    case 'section': {
      const mesh = value['mesh'];
      const { generation, key, x, y, z } = value;
      if (!isNumber(generation) || !isNumber(key) || !isNumber(x) || !isNumber(y) || !isNumber(z) || !isRecord(mesh)) return null;
      const solid = mesh['solid'];
      const cutout = mesh['cutout'];
      const translucent = mesh['translucent'];
      if (!isLayer(solid) || !isLayer(cutout) || !isLayer(translucent)) return null;
      return { type: 'section', generation, key, x, y, z, mesh: { solid, cutout, translucent } };
    }
    case 'idle':
      return isNumber(value['generation']) ? { type: 'idle', generation: value['generation'] } : null;
    case 'collision': {
      const { world, keys, bits } = value;
      if (!isNumber(world) || !(keys instanceof Float64Array) || !(bits instanceof Uint8Array)) return null;
      if (bits.length !== keys.length * SECTION_BIT_BYTES) return null;
      return { type: 'collision', world, keys, bits };
    }
    case 'error':
      return typeof value['message'] === 'string' ? { type: 'error', message: value['message'] } : null;
    default:
      return null;
  }
}

function isFiles(value: unknown): value is Map<string, Uint8Array<ArrayBuffer>> {
  return value instanceof Map && [...value.entries()].every(([key, data]) => typeof key === 'string' && data instanceof Uint8Array);
}

function isTriple(value: unknown): value is readonly [number, number, number] {
  return Array.isArray(value) && value.length === 3 && value.every(isNumber);
}

function isChange(value: unknown): value is BlockChange {
  return isRecord(value) && isNumber(value['x']) && isNumber(value['y']) && isNumber(value['z']) && isNumber(value['state']);
}

function isWireWorld(value: unknown): value is WireWorld {
  return isRecord(value) && isNumber(value['world']) && isWorldDimension(value['dimension'])
    && Array.isArray(value['sections']) && value['sections'].every(isSection);
}

export function parseWorldRequest(value: unknown): WorldRequest | null {
  if (!isRecord(value)) return null;
  switch (value['type']) {
    case 'init': {
      const { version, primary, models, palette, biomeKeys, itemKeys, worlds } = value;
      if (typeof version !== 'string' || !isFiles(primary) || !(models === null || isFiles(models))) return null;
      if (!Array.isArray(palette) || !palette.every(isBlockState) || !isStringList(biomeKeys) || !isStringList(itemKeys)) return null;
      if (!Array.isArray(worlds) || !worlds.every(isWireWorld)) return null;
      return { type: 'init', version, primary, models, palette, biomeKeys, itemKeys, worlds };
    }
    case 'world': {
      const { generation, world, changes, focus } = value;
      if (!isNumber(generation) || !isNumber(world) || !Array.isArray(changes) || !changes.every(isChange) || !isTriple(focus)) return null;
      return { type: 'world', generation, world, changes, focus };
    }
    case 'changes': {
      const { generation, changes } = value;
      return isNumber(generation) && Array.isArray(changes) && changes.every(isChange) ? { type: 'changes', generation, changes } : null;
    }
    case 'focus': {
      const focus = value['focus'];
      return isTriple(focus) ? { type: 'focus', focus } : null;
    }
    default:
      return null;
  }
}

export function meshTransfers(mesh: SectionMesh): ArrayBuffer[] {
  return [mesh.solid, mesh.cutout, mesh.translucent].flatMap((layer) => [
    layer.positions.buffer,
    layer.uvs.buffer,
    layer.colors.buffer,
    layer.lights.buffer,
    layer.indices.buffer,
  ]).filter((buffer): buffer is ArrayBuffer => buffer instanceof ArrayBuffer);
}

export function sectionTransfers(worlds: readonly WireWorld[]): ArrayBuffer[] {
  return worlds.flatMap((world) => world.sections.flatMap((section) => (section.states === null ? [] : [section.states.buffer])));
}
