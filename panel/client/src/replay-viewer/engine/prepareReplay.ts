import { SECTION_VOLUME, type SectionStates, blockOrigin, blockSectionKey, localIndex, sectionKey } from '../format/WorldLayout.ts';
import { readReplay } from '../format/readReplay.ts';
import { DEFAULT_DIMENSION, type ParsedReplay, type ReplayWorld } from '../format/ReplayModel.ts';
import { isBlockEntityState } from '../render/entities/blockEntityStates.ts';
import { buildTimelineData } from './buildTimeline.ts';
import type { PreparedReplay, PreparedWorld } from './PreparedReplay.ts';

function itemKeys(replay: ParsedReplay): string[] {
  const keys = new Set<string>();
  const addStack = (item: string | undefined): void => {
    if (item !== undefined) keys.add(item);
  };
  for (const event of replay.events) {
    if (event.kind === 'equipment') for (const piece of event.equipment) addStack(piece.item?.item);
    if (event.kind === 'spawn') {
      for (const piece of event.entity.equipment) addStack(piece.item?.item);
      addStack(event.entity.metadata.item?.item);
    }
    if (event.kind === 'metadata') addStack(event.metadata.item?.item);
    if (event.kind === 'target_inventory') for (const slot of event.slots) addStack(slot.item?.item);
  }
  for (const keyframe of replay.keyframes) {
    for (const entity of keyframe.entities) {
      for (const piece of entity.equipment) addStack(piece.item?.item);
      addStack(entity.metadata.item?.item);
    }
    for (const slot of keyframe.target?.inventory ?? []) addStack(slot.item?.item);
  }
  return [...keys];
}

function scanBlockEntities(world: ReplayWorld, handled: ReadonlySet<number>): Int32Array<ArrayBuffer> {
  const sites: number[] = [];
  if (handled.size === 0) return new Int32Array();
  for (const section of world.sections) {
    const states = section.states;
    if (states === null) continue;
    for (let index = 0; index < SECTION_VOLUME; index++) {
      const state = states[index] ?? 0;
      if (handled.has(state)) sites.push(...blockOrigin(section.x, section.y, section.z, index), state);
    }
  }
  return Int32Array.from(sites);
}

function initialStates(replay: ParsedReplay): (world: number, x: number, y: number, z: number) => number {
  const worlds = new Map<number, Map<number, SectionStates>>();
  for (const [index, world] of replay.worlds) {
    const sections = new Map<number, SectionStates>();
    for (const section of world.sections) {
      if (section.states !== null) sections.set(sectionKey(section.x, section.y, section.z), section.states);
    }
    worlds.set(index, sections);
  }
  return (world, x, y, z) => worlds.get(world)?.get(blockSectionKey(x, y, z))?.[localIndex(x, y, z)] ?? 0;
}

export async function prepareReplayData(buffer: ArrayBuffer): Promise<PreparedReplay> {
  const replay = await readReplay(buffer);
  const timeline = buildTimelineData(replay, initialStates(replay));
  const handled = new Set<number>();
  replay.palette.forEach((state, index) => {
    if (isBlockEntityState(state)) handled.add(index);
  });
  const worlds = new Map<number, PreparedWorld>();
  for (const [index, world] of replay.worlds) {
    worlds.set(index, { dimension: world.dimension, sections: world.sections, blockEntities: scanBlockEntities(world, handled) });
  }
  for (const index of timeline.worlds.values) {
    if (!worlds.has(index)) worlds.set(index, { dimension: { ...DEFAULT_DIMENSION, world: index }, sections: [], blockEntities: new Int32Array() });
  }
  return { header: replay.header, palette: replay.palette, biomeKeys: replay.biomeKeys, itemKeys: itemKeys(replay), worlds, timeline };
}
