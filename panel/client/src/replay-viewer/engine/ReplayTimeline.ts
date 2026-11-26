import type { EffectData, EntityMetadataData, EquipmentSlotName, ItemStackData, PlayerInfoData } from '../format/ReplayModel.ts';
import type { ReplayMarker } from './ReplayPlayer.ts';
import { Timeline, searchTimes } from './Timeline.ts';
import {
  BLOCK_STRIDE,
  type BlockChangeData,
  type ChatEntry,
  type DayCycle,
  type EquipmentState,
  POSITION_ON_GROUND,
  POSITION_STRIDE,
  POSITION_TELEPORT,
  type TargetSnapshot,
  type TimelineData,
  type TrackData,
} from './TimelineData.ts';

export type { ChatEntry, DayCycle, EquipmentState, TargetSnapshot } from './TimelineData.ts';

export interface EntityPose {
  readonly x: number;
  readonly y: number;
  readonly z: number;
  readonly yaw: number;
  readonly pitch: number;
  readonly headYaw: number;
  readonly onGround: boolean;
  readonly speed: number;
  readonly walkDistance: number;
}

export interface TimedBlockChange {
  readonly timeMs: number;
  readonly world: number;
  readonly x: number;
  readonly y: number;
  readonly z: number;
  readonly state: number;
  readonly previous: number;
}

export const TELEPORT_DISTANCE = 8;
const SWING_MS = 300;
const HURT_MS = 500;
export const NOON = 6000;

export const EMPTY_TARGET: TargetSnapshot = {
  health: null,
  maxHealth: null,
  absorption: null,
  food: null,
  saturation: null,
  armor: null,
  xpLevel: null,
  xpProgress: null,
  selectedSlot: null,
  gameMode: null,
  inventory: new Map(),
};

const MENU_ARMOR: readonly (readonly [number, EquipmentSlotName])[] = [[5, 'head'], [6, 'chest'], [7, 'legs'], [8, 'feet'], [45, 'offhand']];

export function lerpAngle(from: number, to: number, t: number): number {
  let delta = ((to - from) % 360 + 540) % 360 - 180;
  if (!Number.isFinite(delta)) delta = 0;
  return from + delta * t;
}

function recentProgress(times: Float64Array, time: number, duration: number): number | null {
  const index = searchTimes(times, time);
  if (index < 0) return null;
  const elapsed = time - (times[index] ?? 0);
  return elapsed < duration ? elapsed / duration : null;
}

export class EntityTrack {
  readonly id: number;
  readonly type: string;
  readonly uuid: string | null;
  readonly metadata: Timeline<EntityMetadataData>;
  readonly equipment: Timeline<EquipmentState>;
  readonly effects: Timeline<readonly EffectData[]>;
  readonly vehicle: Timeline<number | null>;
  private readonly presence: Float64Array;
  private readonly positionTimes: Float64Array;
  private readonly positions: Float64Array;
  private readonly swings: Float64Array;
  private readonly offhandSwings: Float64Array;
  private readonly hurts: Float64Array;
  private readonly deaths: Float64Array;

  constructor(data: TrackData) {
    this.id = data.id;
    this.type = data.type;
    this.uuid = data.uuid;
    this.presence = data.presence;
    this.positionTimes = data.positionTimes;
    this.positions = data.positions;
    this.metadata = new Timeline(data.metadata);
    this.equipment = new Timeline(data.equipment);
    this.effects = new Timeline(data.effects);
    this.vehicle = new Timeline(data.vehicle);
    this.swings = data.swings;
    this.offhandSwings = data.offhandSwings;
    this.hurts = data.hurts;
    this.deaths = data.deaths;
  }

  present(time: number): boolean {
    return this.presenceStart(time) !== null;
  }

  private presenceStart(time: number): number | null {
    for (let index = 0; index + 1 < this.presence.length; index += 2) {
      const start = this.presence[index] ?? 0;
      if (time >= start && time < (this.presence[index + 1] ?? 0)) return start;
    }
    return null;
  }

  private column(index: number, offset: number): number {
    return this.positions[index * POSITION_STRIDE + offset] ?? 0;
  }

  private flag(index: number, bit: number): boolean {
    return (this.column(index, 6) & bit) !== 0;
  }

  pose(time: number): EntityPose | null {
    const count = this.positionTimes.length;
    if (count === 0) return null;
    const found = searchTimes(this.positionTimes, time);
    const index = Math.max(0, found);
    const distance = this.column(index, 7);
    const resting = (): EntityPose => ({
      x: this.column(index, 0),
      y: this.column(index, 1),
      z: this.column(index, 2),
      yaw: this.column(index, 3),
      pitch: this.column(index, 4),
      headYaw: this.column(index, 5),
      onGround: this.flag(index, POSITION_ON_GROUND),
      speed: 0,
      walkDistance: distance,
    });
    const next = index + 1;
    if (found < 0 || next >= count || this.flag(next, POSITION_TELEPORT)) return resting();
    const startTime = this.positionTimes[index] ?? 0;
    const span = Math.max(1, (this.positionTimes[next] ?? 0) - startTime);
    const t = Math.min(1, Math.max(0, (time - startTime) / span));
    const dx = this.column(next, 0) - this.column(index, 0);
    const dy = this.column(next, 1) - this.column(index, 1);
    const dz = this.column(next, 2) - this.column(index, 2);
    if (Math.hypot(dx, dy, dz) > TELEPORT_DISTANCE) return resting();
    const horizontal = Math.hypot(dx, dz);
    return {
      x: this.column(index, 0) + dx * t,
      y: this.column(index, 1) + dy * t,
      z: this.column(index, 2) + dz * t,
      yaw: lerpAngle(this.column(index, 3), this.column(next, 3), t),
      pitch: this.column(index, 4) + (this.column(next, 4) - this.column(index, 4)) * t,
      headYaw: lerpAngle(this.column(index, 5), this.column(next, 5), t),
      onGround: this.flag(next, POSITION_ON_GROUND),
      speed: horizontal / (span / 1000),
      walkDistance: distance + horizontal * t,
    };
  }

  swingProgress(time: number, offhand: boolean): number | null {
    return recentProgress(offhand ? this.offhandSwings : this.swings, time, SWING_MS);
  }

  hurtProgress(time: number): number | null {
    return recentProgress(this.hurts, time, HURT_MS);
  }

  deathTime(time: number): number | null {
    const index = searchTimes(this.deaths, time);
    if (index < 0) return null;
    const found = this.deaths[index] ?? 0;
    return found >= (this.presenceStart(time) ?? 0) ? time - found : null;
  }
}

export class BlockChanges {
  private readonly times: Float64Array;
  private readonly cells: Int32Array;

  constructor(data: BlockChangeData) {
    this.times = data.times;
    this.cells = data.cells;
  }

  get size(): number {
    return this.times.length;
  }

  timeAt(index: number): number {
    return this.times[index] ?? Infinity;
  }

  at(index: number): TimedBlockChange | null {
    if (index < 0 || index >= this.times.length) return null;
    const offset = index * BLOCK_STRIDE;
    const cell = (column: number): number => this.cells[offset + column] ?? 0;
    return { timeMs: this.timeAt(index), world: cell(0), x: cell(1), y: cell(2), z: cell(3), state: cell(4), previous: cell(5) };
  }
}

export class ReplayTimeline {
  readonly durationMs: number;
  readonly entities = new Map<number, EntityTrack>();
  readonly players = new Map<string, Timeline<PlayerInfoData | null>>();
  readonly target: Timeline<TargetSnapshot>;
  readonly chat: readonly ChatEntry[];
  readonly blocks: BlockChanges;
  readonly worlds: Timeline<number>;
  readonly day: Timeline<DayCycle>;
  readonly markers: readonly ReplayMarker[];
  readonly targetEntityId: number | null;
  readonly targetUuid: string | null;

  constructor(data: TimelineData) {
    this.durationMs = data.durationMs;
    this.targetEntityId = data.targetEntityId;
    this.targetUuid = data.targetUuid;
    for (const track of data.tracks) this.entities.set(track.id, new EntityTrack(track));
    for (const [uuid, timeline] of data.players) this.players.set(uuid, new Timeline(timeline));
    this.target = new Timeline(data.target);
    this.chat = data.chat;
    this.blocks = new BlockChanges(data.blocks);
    this.worlds = new Timeline(data.worlds);
    this.day = new Timeline(data.day);
    this.markers = data.markers;
  }

  worldAt(time: number): number {
    return this.worlds.at(time) ?? this.worlds.valueAt(0) ?? 0;
  }

  dayAt(time: number): DayCycle {
    return this.day.at(time) ?? { timeOfDay: NOON, rain: 0, thunder: 0 };
  }

  targetAt(time: number): TargetSnapshot {
    return this.target.at(time) ?? EMPTY_TARGET;
  }

  playerInfo(uuid: string, time: number): PlayerInfoData | null {
    const timeline = this.players.get(uuid);
    if (timeline === undefined) return null;
    return timeline.at(time) ?? timeline.valueAt(0) ?? null;
  }

  targetEquipment(time: number): EquipmentState {
    const snapshot = this.targetAt(time);
    const equipment: Partial<Record<EquipmentSlotName, ItemStackData | null>> = {};
    for (const [slot, name] of MENU_ARMOR) equipment[name] = snapshot.inventory.get(slot) ?? null;
    const selected = snapshot.selectedSlot;
    if (selected !== null) equipment.mainhand = snapshot.inventory.get(36 + selected) ?? null;
    return equipment;
  }
}
