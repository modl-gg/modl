import { isRecord } from '../format/guards.ts';
import {
  type ChatKind,
  CHAT_KINDS,
  type EffectData,
  type EntityFlags,
  type EntityMetadataData,
  EQUIPMENT_SLOTS,
  type EquipmentSlotName,
  GAME_MODES,
  type GameModeName,
  type ItemStackData,
  type PlayerInfoData,
  POSES,
} from '../format/ReplayModel.ts';
import { MARKER_KINDS, type MarkerKind, type ReplayMarker } from './ReplayPlayer.ts';
import type { TimelineSeries } from './Timeline.ts';

export const POSITION_STRIDE = 8;
export const POSITION_ON_GROUND = 1;
export const POSITION_TELEPORT = 2;
export const BLOCK_STRIDE = 6;

export type EquipmentState = Readonly<Partial<Record<EquipmentSlotName, ItemStackData | null>>>;

export interface TargetSnapshot {
  readonly health: number | null;
  readonly maxHealth: number | null;
  readonly absorption: number | null;
  readonly food: number | null;
  readonly saturation: number | null;
  readonly armor: number | null;
  readonly xpLevel: number | null;
  readonly xpProgress: number | null;
  readonly selectedSlot: number | null;
  readonly gameMode: GameModeName | null;
  readonly inventory: ReadonlyMap<number, ItemStackData>;
}

export interface ChatEntry {
  readonly timeMs: number;
  readonly kind: ChatKind;
  readonly json: string;
  readonly senderUuid: string | null;
}

export interface DayCycle {
  readonly timeOfDay: number;
  readonly rain: number;
  readonly thunder: number;
}

export interface TrackData {
  readonly id: number;
  readonly type: string;
  readonly uuid: string | null;
  readonly presence: Float64Array<ArrayBuffer>;
  readonly positionTimes: Float64Array<ArrayBuffer>;
  readonly positions: Float64Array<ArrayBuffer>;
  readonly metadata: TimelineSeries<EntityMetadataData>;
  readonly equipment: TimelineSeries<EquipmentState>;
  readonly effects: TimelineSeries<readonly EffectData[]>;
  readonly vehicle: TimelineSeries<number | null>;
  readonly swings: Float64Array<ArrayBuffer>;
  readonly offhandSwings: Float64Array<ArrayBuffer>;
  readonly hurts: Float64Array<ArrayBuffer>;
  readonly deaths: Float64Array<ArrayBuffer>;
}

export interface BlockChangeData {
  readonly times: Float64Array<ArrayBuffer>;
  readonly cells: Int32Array<ArrayBuffer>;
}

export interface TimelineData {
  readonly durationMs: number;
  readonly targetEntityId: number | null;
  readonly targetUuid: string | null;
  readonly tracks: readonly TrackData[];
  readonly players: ReadonlyMap<string, TimelineSeries<PlayerInfoData | null>>;
  readonly target: TimelineSeries<TargetSnapshot>;
  readonly chat: readonly ChatEntry[];
  readonly blocks: BlockChangeData;
  readonly worlds: TimelineSeries<number>;
  readonly day: TimelineSeries<DayCycle>;
  readonly markers: readonly ReplayMarker[];
}

type Guard<T> = (value: unknown) => value is T;

function isNumber(value: unknown): value is number {
  return typeof value === 'number';
}

function isString(value: unknown): value is string {
  return typeof value === 'string';
}

function isBoolean(value: unknown): value is boolean {
  return typeof value === 'boolean';
}

function nullable<T>(guard: Guard<T>): Guard<T | null> {
  return (value): value is T | null => value === null || guard(value);
}

function oneOf<T extends string>(values: readonly T[]): Guard<T> {
  return (value): value is T => values.some((candidate) => candidate === value);
}

function arrayOf<T>(guard: Guard<T>): Guard<readonly T[]> {
  return (value): value is readonly T[] => Array.isArray(value) && value.every(guard);
}

function optional(record: Readonly<Record<string, unknown>>, key: string, guard: Guard<unknown>): boolean {
  const value = record[key];
  return value === undefined || guard(value);
}

function isFloats(value: unknown): value is Float64Array<ArrayBuffer> {
  return value instanceof Float64Array && value.buffer instanceof ArrayBuffer;
}

function series<T>(guard: Guard<T>): Guard<TimelineSeries<T>> {
  return (value): value is TimelineSeries<T> => isRecord(value) && isFloats(value['times']) && Array.isArray(value['values'])
    && value['values'].length === value['times'].length && value['values'].every(guard);
}

const isGameMode = oneOf(GAME_MODES);
const isSlot = oneOf(EQUIPMENT_SLOTS);

const isItem: Guard<ItemStackData> = (value): value is ItemStackData => isRecord(value) && isString(value['item']) && isNumber(value['count'])
  && isBoolean(value['enchanted']) && nullable(isNumber)(value['dyeColor']) && nullable(isString)(value['customName'])
  && isNumber(value['damage']) && isNumber(value['maxDamage']);

const isFlags: Guard<EntityFlags> = (value): value is EntityFlags => isRecord(value)
  && ['onFire', 'sneaking', 'sprinting', 'swimming', 'invisible', 'glowing', 'gliding'].every((key) => isBoolean(value[key]));

const isMetadata: Guard<EntityMetadataData> = (value): value is EntityMetadataData => isRecord(value)
  && optional(value, 'flags', isFlags) && optional(value, 'pose', oneOf(POSES)) && optional(value, 'item', nullable(isItem))
  && optional(value, 'customName', isString) && optional(value, 'customNameVisible', isBoolean) && optional(value, 'baby', isBoolean)
  && optional(value, 'health', isNumber) && optional(value, 'blockState', isNumber) && optional(value, 'skinParts', isNumber)
  && optional(value, 'leftHanded', isBoolean) && optional(value, 'variant', isNumber);

const isEquipment: Guard<EquipmentState> = (value): value is EquipmentState => isRecord(value)
  && Object.entries(value).every(([slot, stack]) => isSlot(slot) && nullable(isItem)(stack));

const isEffect: Guard<EffectData> = (value): value is EffectData => isRecord(value) && isString(value['effect'])
  && isNumber(value['amplifier']) && isNumber(value['durationTicks']);

const isPlayer: Guard<PlayerInfoData> = (value): value is PlayerInfoData => isRecord(value) && isString(value['uuid']) && isString(value['name'])
  && nullable(isString)(value['skinHash']) && isBoolean(value['slim']) && nullable(isGameMode)(value['gameMode'])
  && (value['skinPng'] === null || (value['skinPng'] instanceof Uint8Array && value['skinPng'].buffer instanceof ArrayBuffer));

function inventoryGuard(): Guard<ReadonlyMap<number, ItemStackData>> {
  const checked = new WeakSet<object>();
  return (value): value is ReadonlyMap<number, ItemStackData> => {
    if (!(value instanceof Map)) return false;
    if (checked.has(value)) return true;
    const valid = [...value.entries()].every(([slot, stack]) => isNumber(slot) && isItem(stack));
    if (valid) checked.add(value);
    return valid;
  };
}

function targetGuard(): Guard<TargetSnapshot> {
  const isInventory = inventoryGuard();
  return (value): value is TargetSnapshot => isRecord(value)
    && ['health', 'maxHealth', 'absorption', 'food', 'saturation', 'armor', 'xpLevel', 'xpProgress', 'selectedSlot'].every((key) => nullable(isNumber)(value[key]))
    && nullable(isGameMode)(value['gameMode']) && isInventory(value['inventory']);
}

const isChat: Guard<ChatEntry> = (value): value is ChatEntry => isRecord(value) && isNumber(value['timeMs'])
  && oneOf(CHAT_KINDS)(value['kind']) && isString(value['json']) && nullable(isString)(value['senderUuid']);

const isDay: Guard<DayCycle> = (value): value is DayCycle => isRecord(value) && isNumber(value['timeOfDay'])
  && isNumber(value['rain']) && isNumber(value['thunder']);

const isMarker: Guard<ReplayMarker> = (value): value is ReplayMarker => isRecord(value) && isNumber(value['timeMs'])
  && oneOf<MarkerKind>(MARKER_KINDS)(value['kind']);

const isTrack: Guard<TrackData> = (value): value is TrackData => isRecord(value) && isNumber(value['id']) && isString(value['type'])
  && nullable(isString)(value['uuid']) && isFloats(value['presence']) && isFloats(value['positionTimes']) && isFloats(value['positions'])
  && value['positions'].length === value['positionTimes'].length * POSITION_STRIDE
  && series(isMetadata)(value['metadata']) && series(isEquipment)(value['equipment']) && series(arrayOf(isEffect))(value['effects'])
  && series(nullable(isNumber))(value['vehicle'])
  && isFloats(value['swings']) && isFloats(value['offhandSwings']) && isFloats(value['hurts']) && isFloats(value['deaths']);

const isBlocks: Guard<BlockChangeData> = (value): value is BlockChangeData => isRecord(value) && isFloats(value['times'])
  && value['cells'] instanceof Int32Array && value['cells'].buffer instanceof ArrayBuffer && value['cells'].length === value['times'].length * BLOCK_STRIDE;

const isPlayers: Guard<ReadonlyMap<string, TimelineSeries<PlayerInfoData | null>>> = (value): value is ReadonlyMap<string, TimelineSeries<PlayerInfoData | null>> => value instanceof Map
  && [...value.entries()].every(([uuid, timeline]) => isString(uuid) && series(nullable(isPlayer))(timeline));

export function parseTimelineData(value: unknown): TimelineData | null {
  if (!isRecord(value)) return null;
  const { durationMs, targetEntityId, targetUuid, tracks, players, target, chat, blocks, worlds, day, markers } = value;
  if (!isNumber(durationMs) || !nullable(isNumber)(targetEntityId) || !nullable(isString)(targetUuid)) return null;
  if (!arrayOf(isTrack)(tracks) || !isPlayers(players) || !series(targetGuard())(target) || !arrayOf(isChat)(chat)) return null;
  if (!isBlocks(blocks) || !series(isNumber)(worlds) || !series(isDay)(day) || !arrayOf(isMarker)(markers)) return null;
  return { durationMs, targetEntityId, targetUuid, tracks, players, target, chat, blocks, worlds, day, markers };
}

export function timelineTransfers(data: TimelineData): ArrayBuffer[] {
  const buffers: ArrayBuffer[] = [data.blocks.times.buffer, data.blocks.cells.buffer, data.target.times.buffer, data.worlds.times.buffer, data.day.times.buffer];
  for (const track of data.tracks) {
    buffers.push(track.presence.buffer, track.positionTimes.buffer, track.positions.buffer, track.metadata.times.buffer, track.equipment.times.buffer,
      track.effects.times.buffer, track.vehicle.times.buffer, track.swings.buffer, track.offhandSwings.buffer, track.hurts.buffer, track.deaths.buffer);
  }
  for (const player of data.players.values()) buffers.push(player.times.buffer);
  return buffers;
}
