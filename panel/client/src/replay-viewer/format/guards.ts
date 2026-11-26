import type { BlockState } from './BlockState.ts';
import type { DimensionInfo, ReplaySection, WorldDimension } from './ReplayModel.ts';
import { BIOME_CELLS, LIGHT_BYTES, SECTION_VOLUME, type SectionStates } from './WorldLayout.ts';

export type JsonRecord = Readonly<Record<string, unknown>>;

export function isRecord(value: unknown): value is JsonRecord {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

export function isStringList(value: unknown): value is readonly string[] {
  return Array.isArray(value) && value.every((entry) => typeof entry === 'string');
}

export function isBlockState(value: unknown): value is BlockState {
  return isRecord(value) && typeof value['name'] === 'string' && isRecord(value['properties'])
    && Object.values(value['properties']).every((entry) => typeof entry === 'string');
}

export function isWorldDimension(value: unknown): value is WorldDimension {
  return isRecord(value) && typeof value['key'] === 'string' && isNumber(value['minY']) && isNumber(value['height']) && typeof value['hasSkyLight'] === 'boolean';
}

export function isDimensionInfo(value: unknown): value is DimensionInfo {
  return isRecord(value) && isWorldDimension(value) && isNumber(value['world']);
}

function isStates(value: unknown): value is SectionStates | null {
  return value === null || ((value instanceof Uint16Array || value instanceof Uint32Array) && value.buffer instanceof ArrayBuffer && value.length === SECTION_VOLUME);
}

function isLight(value: unknown): value is Uint8Array | null {
  return value === null || (value instanceof Uint8Array && value.length === LIGHT_BYTES);
}

export function isSection(value: unknown): value is ReplaySection {
  return isRecord(value) && isNumber(value['x']) && isNumber(value['y']) && isNumber(value['z'])
    && isStates(value['states']) && isLight(value['skyLight']) && isLight(value['blockLight'])
    && (value['biomes'] === null || (value['biomes'] instanceof Uint32Array && value['biomes'].length === BIOME_CELLS));
}
