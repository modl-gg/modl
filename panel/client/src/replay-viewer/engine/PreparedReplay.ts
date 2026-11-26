import type { BlockState } from '../format/BlockState.ts';
import { isBlockState, isDimensionInfo, isRecord, isSection, isStringList } from '../format/guards.ts';
import type { DimensionInfo, ReplayHeader, ReplaySection } from '../format/ReplayModel.ts';
import { type TimelineData, parseTimelineData, timelineTransfers } from './TimelineData.ts';

export const BLOCK_ENTITY_STRIDE = 4;

export interface PreparedWorld {
  readonly dimension: DimensionInfo;
  readonly sections: readonly ReplaySection[];
  readonly blockEntities: Int32Array<ArrayBuffer>;
}

export interface PreparedReplay {
  readonly header: ReplayHeader;
  readonly palette: readonly BlockState[];
  readonly biomeKeys: readonly string[];
  readonly itemKeys: readonly string[];
  readonly worlds: ReadonlyMap<number, PreparedWorld>;
  readonly timeline: TimelineData;
}

function isNullableNumber(value: unknown): value is number | null {
  return value === null || typeof value === 'number';
}

function isNullableString(value: unknown): value is string | null {
  return value === null || typeof value === 'string';
}

function isHeader(value: unknown): value is ReplayHeader {
  return isRecord(value)
    && ['formatVersion', 'minY', 'height', 'startEpochMs', 'durationMs', 'radiusBlocks'].every((key) => typeof value[key] === 'number')
    && typeof value['minecraftVersion'] === 'string' && typeof value['dimension'] === 'string'
    && isNullableString(value['targetUuid']) && isNullableString(value['targetName'])
    && isNullableNumber(value['markerMs']) && isNullableNumber(value['targetEntityId'])
    && ['hardcore', 'lightRecorded', 'truncated'].every((key) => typeof value[key] === 'boolean');
}

function isPreparedWorld(value: unknown): value is PreparedWorld {
  return isRecord(value) && isDimensionInfo(value['dimension'])
    && Array.isArray(value['sections']) && value['sections'].every(isSection)
    && value['blockEntities'] instanceof Int32Array && value['blockEntities'].buffer instanceof ArrayBuffer
    && value['blockEntities'].length % BLOCK_ENTITY_STRIDE === 0;
}

function isWorlds(value: unknown): value is ReadonlyMap<number, PreparedWorld> {
  return value instanceof Map && [...value.entries()].every(([index, world]) => typeof index === 'number' && isPreparedWorld(world));
}

export function parsePreparedReplay(value: unknown): PreparedReplay | null {
  if (!isRecord(value)) return null;
  const { header, palette, biomeKeys, itemKeys, worlds } = value;
  if (!isHeader(header) || !Array.isArray(palette) || !palette.every(isBlockState) || !isStringList(biomeKeys) || !isStringList(itemKeys) || !isWorlds(worlds)) return null;
  const timeline = parseTimelineData(value['timeline']);
  return timeline === null ? null : { header, palette, biomeKeys, itemKeys, worlds, timeline };
}

export function preparedTransfers(replay: PreparedReplay): ArrayBuffer[] {
  const buffers = timelineTransfers(replay.timeline);
  for (const world of replay.worlds.values()) {
    buffers.push(world.blockEntities.buffer);
    for (const section of world.sections) {
      for (const data of [section.states, section.skyLight, section.blockLight, section.biomes]) {
        if (data !== null && data.buffer instanceof ArrayBuffer) buffers.push(data.buffer);
      }
    }
  }
  return [...new Set(buffers)];
}
