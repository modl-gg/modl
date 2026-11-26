import type {
  EffectData,
  EntityMetadataData,
  EntitySnapshotData,
  EquipmentData,
  EquipmentSlotName,
  ItemStackData,
  KeyframeData,
  ParsedReplay,
  PlayerInfoData,
  ReplayEventData,
} from '../format/ReplayModel.ts';
import { SparseBlockMap } from '../format/WorldLayout.ts';
import type { MarkerKind, ReplayMarker } from './ReplayPlayer.ts';
import { EMPTY_TARGET, NOON, TELEPORT_DISTANCE } from './ReplayTimeline.ts';
import { TimelineBuilder, type TimelineSeries } from './Timeline.ts';
import {
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

export type InitialState = (world: number, x: number, y: number, z: number) => number;

interface PositionSample {
  readonly x: number;
  readonly y: number;
  readonly z: number;
  readonly yaw: number;
  readonly pitch: number;
  readonly headYaw: number;
  readonly onGround: boolean;
  readonly teleport: boolean;
}

const MAX_MARKERS_PER_KIND = 600;

function dayTime(recorded: number): number {
  if (!Number.isFinite(recorded) || recorded === -1) return NOON;
  return Math.abs(recorded);
}

function mergeMetadata(previous: EntityMetadataData | undefined, update: EntityMetadataData): EntityMetadataData {
  return { ...(previous ?? {}), ...update };
}

function mergeEquipment(previous: EquipmentState | undefined, updates: readonly EquipmentData[]): EquipmentState {
  const next: Partial<Record<EquipmentSlotName, ItemStackData | null>> = { ...(previous ?? {}) };
  for (const update of updates) next[update.slot] = update.item;
  return next;
}

class TrackBuilder {
  readonly id: number;
  readonly type: string;
  readonly uuid: string | null;
  readonly metadata = new TimelineBuilder<EntityMetadataData>();
  readonly equipment = new TimelineBuilder<EquipmentState>();
  readonly effects = new TimelineBuilder<readonly EffectData[]>();
  readonly vehicle = new TimelineBuilder<number | null>();
  readonly swings: number[] = [];
  readonly offhandSwings: number[] = [];
  readonly hurts: number[] = [];
  readonly deaths: number[] = [];
  private readonly presence: number[] = [];
  private readonly positionTimes: number[] = [];
  private readonly positions: number[] = [];

  constructor(id: number, type: string, uuid: string | null) {
    this.id = id;
    this.type = type;
    this.uuid = uuid;
  }

  isOpen(): boolean {
    return this.presence.at(-1) === Infinity;
  }

  open(time: number): void {
    if (this.isOpen()) return;
    this.presence.push(time, Infinity);
  }

  close(time: number): void {
    if (this.isOpen()) this.presence[this.presence.length - 1] = time;
  }

  get positionCount(): number {
    return this.positionTimes.length;
  }

  lastPosition(): PositionSample | undefined {
    const count = this.positionTimes.length;
    if (count === 0) return undefined;
    const offset = (count - 1) * POSITION_STRIDE;
    const value = (column: number): number => this.positions[offset + column] ?? 0;
    const flags = value(6);
    return {
      x: value(0),
      y: value(1),
      z: value(2),
      yaw: value(3),
      pitch: value(4),
      headYaw: value(5),
      onGround: (flags & POSITION_ON_GROUND) !== 0,
      teleport: (flags & POSITION_TELEPORT) !== 0,
    };
  }

  addPosition(time: number, sample: PositionSample): void {
    const count = this.positionTimes.length;
    const previous = this.lastPosition();
    const travelled = previous === undefined || sample.teleport ? 0 : Math.hypot(sample.x - previous.x, sample.z - previous.z);
    const total = (count === 0 ? 0 : this.positions[(count - 1) * POSITION_STRIDE + 7] ?? 0) + travelled;
    const flags = (sample.onGround ? POSITION_ON_GROUND : 0) | (sample.teleport ? POSITION_TELEPORT : 0);
    const row = [sample.x, sample.y, sample.z, sample.yaw, sample.pitch, sample.headYaw, flags, total];
    if (count > 0 && this.positionTimes[count - 1] === time) {
      this.positions.splice((count - 1) * POSITION_STRIDE, POSITION_STRIDE, ...row);
      return;
    }
    this.positionTimes.push(time);
    this.positions.push(...row);
  }

  build(): TrackData {
    return {
      id: this.id,
      type: this.type,
      uuid: this.uuid,
      presence: Float64Array.from(this.presence),
      positionTimes: Float64Array.from(this.positionTimes),
      positions: Float64Array.from(this.positions),
      metadata: this.metadata.build(),
      equipment: this.equipment.build(),
      effects: this.effects.build(),
      vehicle: this.vehicle.build(),
      swings: Float64Array.from(this.swings),
      offhandSwings: Float64Array.from(this.offhandSwings),
      hurts: Float64Array.from(this.hurts),
      deaths: Float64Array.from(this.deaths),
    };
  }
}

class MarkerSet {
  private readonly bucketMs: number;
  private readonly seen = new Set<string>();
  readonly markers: ReplayMarker[] = [];

  constructor(durationMs: number) {
    this.bucketMs = Math.max(1, durationMs / MAX_MARKERS_PER_KIND);
  }

  add(timeMs: number, kind: MarkerKind): void {
    const key = `${kind}:${String(Math.floor(timeMs / this.bucketMs))}`;
    if (this.seen.has(key)) return;
    this.seen.add(key);
    this.markers.push({ timeMs, kind });
  }

  sorted(): ReplayMarker[] {
    return this.markers.sort((left, right) => left.timeMs - right.timeMs);
  }
}

class TimelineAssembler {
  private readonly durationMs: number;
  private readonly entities = new Map<number, TrackBuilder>();
  private readonly players = new Map<string, TimelineBuilder<PlayerInfoData | null>>();
  private readonly target = new TimelineBuilder<TargetSnapshot>();
  private readonly chat: ChatEntry[] = [];
  private readonly blockTimes: number[] = [];
  private readonly blockCells: number[] = [];
  private readonly worlds = new TimelineBuilder<number>();
  private readonly day = new TimelineBuilder<DayCycle>();
  private readonly markers: MarkerSet;
  private readonly overlay = new Map<number, SparseBlockMap<number>>();
  private readonly initialState: InitialState;

  constructor(durationMs: number, initialWorld: number, initialState: InitialState) {
    this.durationMs = durationMs;
    this.initialState = initialState;
    this.markers = new MarkerSet(durationMs);
    this.worlds.push(0, initialWorld);
    this.day.push(0, { timeOfDay: NOON, rain: 0, thunder: 0 });
    this.target.push(0, EMPTY_TARGET);
  }

  private track(entity: EntitySnapshotData): TrackBuilder {
    let track = this.entities.get(entity.entityId);
    if (track === undefined || (track.type !== entity.type && !track.isOpen())) {
      track = new TrackBuilder(entity.entityId, entity.type, entity.uuid);
      this.entities.set(entity.entityId, track);
    }
    return track;
  }

  private snapshot(time: number, entity: EntitySnapshotData, full: boolean): void {
    const track = this.track(entity);
    track.open(time);
    track.addPosition(time, { x: entity.x, y: entity.y, z: entity.z, yaw: entity.yaw, pitch: entity.pitch, headYaw: entity.headYaw, onGround: entity.onGround, teleport: full });
    track.metadata.push(time, mergeMetadata(full ? undefined : track.metadata.last(), entity.metadata));
    track.equipment.push(time, mergeEquipment(undefined, entity.equipment));
    track.effects.push(time, entity.effects);
    track.vehicle.push(time, entity.vehicle);
  }

  keyframe(keyframe: KeyframeData): void {
    const time = keyframe.timeMs;
    const seen = new Set<number>();
    for (const entity of keyframe.entities) {
      seen.add(entity.entityId);
      const track = this.entities.get(entity.entityId);
      if (track?.isOpen() === true && track.positionCount > 0) {
        track.metadata.push(time, mergeMetadata(track.metadata.last(), entity.metadata));
        continue;
      }
      this.snapshot(time, entity, false);
    }
    for (const track of this.entities.values()) {
      if (!seen.has(track.id) && track.isOpen()) track.close(time);
    }
    for (const player of keyframe.players) this.playerTimeline(player.uuid).push(time, player);
    if (keyframe.target !== null) {
      const inventory = new Map<number, ItemStackData>();
      for (const slot of keyframe.target.inventory) {
        if (slot.item !== null) inventory.set(slot.slot, slot.item);
      }
      this.target.push(time, { ...keyframe.target, inventory });
    }
    this.day.push(time, { timeOfDay: dayTime(keyframe.timeOfDay), rain: keyframe.rain, thunder: keyframe.thunder });
  }

  private playerTimeline(uuid: string): TimelineBuilder<PlayerInfoData | null> {
    let timeline = this.players.get(uuid);
    if (timeline === undefined) {
      timeline = new TimelineBuilder();
      this.players.set(uuid, timeline);
    }
    return timeline;
  }

  private updateTarget(time: number, update: (previous: TargetSnapshot) => TargetSnapshot): void {
    this.target.push(time, update(this.target.last() ?? EMPTY_TARGET));
  }

  private block(time: number, world: number, x: number, y: number, z: number, state: number): void {
    let overlay = this.overlay.get(world);
    if (overlay === undefined) {
      overlay = new SparseBlockMap();
      this.overlay.set(world, overlay);
    }
    const previous = overlay.get(x, y, z) ?? this.initialState(world, x, y, z);
    overlay.set(x, y, z, state);
    this.blockTimes.push(time);
    this.blockCells.push(world, x, y, z, state, previous);
    this.markers.add(time, state === 0 ? 'block_break' : 'block_place');
  }

  event(event: ReplayEventData, world: number): number {
    const time = event.timeMs;
    switch (event.kind) {
      case 'spawn':
        this.snapshot(time, event.entity, true);
        return world;
      case 'remove':
        for (const id of event.entityIds) this.entities.get(id)?.close(time);
        return world;
      case 'move': {
        const track = this.entities.get(event.entityId);
        const last = track?.lastPosition();
        if (track === undefined || last === undefined) return world;
        track.addPosition(time, {
          x: last.x + event.dx,
          y: last.y + event.dy,
          z: last.z + event.dz,
          yaw: event.yaw ?? last.yaw,
          pitch: event.pitch ?? last.pitch,
          headYaw: event.headYaw ?? last.headYaw,
          onGround: event.onGround,
          teleport: false,
        });
        return world;
      }
      case 'teleport': {
        const track = this.entities.get(event.entityId);
        if (track === undefined) return world;
        const last = track.lastPosition();
        const far = last === undefined || Math.hypot(event.x - last.x, event.y - last.y, event.z - last.z) > TELEPORT_DISTANCE;
        track.addPosition(time, { x: event.x, y: event.y, z: event.z, yaw: event.yaw, pitch: event.pitch, headYaw: event.yaw, onGround: event.onGround, teleport: far });
        return world;
      }
      case 'metadata': {
        const track = this.entities.get(event.entityId);
        track?.metadata.push(time, mergeMetadata(track.metadata.last(), event.metadata));
        return world;
      }
      case 'equipment': {
        const track = this.entities.get(event.entityId);
        track?.equipment.push(time, mergeEquipment(track.equipment.last(), event.equipment));
        return world;
      }
      case 'animation': {
        const track = this.entities.get(event.entityId);
        if (event.animation === 'swing_main') track?.swings.push(time);
        if (event.animation === 'swing_off') track?.offhandSwings.push(time);
        return world;
      }
      case 'hurt':
        this.entities.get(event.entityId)?.hurts.push(time);
        this.markers.add(time, 'damage');
        return world;
      case 'death':
        this.entities.get(event.entityId)?.deaths.push(time);
        this.markers.add(time, 'death');
        return world;
      case 'effect': {
        const track = this.entities.get(event.entityId);
        if (track === undefined) return world;
        const current = (track.effects.last() ?? []).filter((effect) => effect.effect !== event.effect.effect);
        track.effects.push(time, [...current, event.effect]);
        return world;
      }
      case 'effect_remove': {
        const track = this.entities.get(event.entityId);
        if (track === undefined) return world;
        track.effects.push(time, (track.effects.last() ?? []).filter((effect) => effect.effect !== event.effect));
        return world;
      }
      case 'passengers': {
        const riders = new Set(event.passengers);
        for (const track of this.entities.values()) {
          if (track.vehicle.last() === event.vehicle && !riders.has(track.id)) track.vehicle.push(time, null);
        }
        for (const passenger of riders) this.entities.get(passenger)?.vehicle.push(time, event.vehicle);
        return world;
      }
      case 'pickup':
        this.entities.get(event.collected)?.close(time);
        return world;
      case 'block':
        this.block(time, world, event.x, event.y, event.z, event.state);
        return world;
      case 'break_progress':
        return world;
      case 'player_info':
        this.playerTimeline(event.player.uuid).push(time, event.player);
        return world;
      case 'player_remove':
        for (const uuid of event.uuids) this.playerTimeline(uuid).push(time, null);
        return world;
      case 'chat':
        if (event.chatKind !== 'action_bar') {
          this.chat.push({ timeMs: time, kind: event.chatKind, json: event.json, senderUuid: event.senderUuid });
          this.markers.add(time, 'chat');
        }
        return world;
      case 'target_health':
        this.updateTarget(time, (previous) => {
          if (previous.health !== null && event.health < previous.health) this.markers.add(time, 'damage');
          return { ...previous, health: event.health, food: event.food, saturation: event.saturation };
        });
        return world;
      case 'target_attributes':
        this.updateTarget(time, (previous) => ({
          ...previous,
          maxHealth: event.maxHealth ?? previous.maxHealth,
          absorption: event.absorption ?? previous.absorption,
          armor: event.armor ?? previous.armor,
        }));
        return world;
      case 'target_experience':
        this.updateTarget(time, (previous) => ({ ...previous, xpLevel: event.level, xpProgress: event.progress }));
        return world;
      case 'target_slot':
        this.updateTarget(time, (previous) => ({ ...previous, selectedSlot: event.slot }));
        return world;
      case 'target_inventory':
        this.updateTarget(time, (previous) => {
          const inventory = new Map(event.replaceAll ? [] : previous.inventory);
          for (const slot of event.slots) {
            if (slot.item === null) inventory.delete(slot.slot);
            else inventory.set(slot.slot, slot.item);
          }
          return { ...previous, inventory };
        });
        return world;
      case 'target_gamemode':
        this.updateTarget(time, (previous) => ({ ...previous, gameMode: event.gameMode }));
        return world;
      case 'dimension':
        this.worlds.push(time, event.dimension.world);
        return event.dimension.world;
      case 'time': {
        const previous = this.day.last();
        this.day.push(time, { timeOfDay: dayTime(event.timeOfDay), rain: previous?.rain ?? 0, thunder: previous?.thunder ?? 0 });
        return world;
      }
      case 'weather': {
        const previous = this.day.last();
        this.day.push(time, { timeOfDay: previous?.timeOfDay ?? NOON, rain: event.rain, thunder: event.thunder });
        return world;
      }
    }
  }

  build(targetEntityId: number | null, targetUuid: string | null): TimelineData {
    this.markers.markers.push({ timeMs: this.durationMs, kind: 'capture' });
    const players = new Map<string, TimelineSeries<PlayerInfoData | null>>();
    for (const [uuid, timeline] of this.players) players.set(uuid, timeline.build());
    return {
      durationMs: this.durationMs,
      targetEntityId,
      targetUuid,
      tracks: [...this.entities.values()].map((track) => track.build()),
      players,
      target: this.target.build(),
      chat: this.chat,
      blocks: { times: Float64Array.from(this.blockTimes), cells: Int32Array.from(this.blockCells) },
      worlds: this.worlds.build(),
      day: this.day.build(),
      markers: this.markers.sorted(),
    };
  }
}

export function buildTimelineData(replay: ParsedReplay, initialState: InitialState): TimelineData {
  const durationMs = Math.max(1, replay.header.durationMs);
  const keyframes = [...replay.keyframes].sort((left, right) => left.timeMs - right.timeMs);
  const initialWorld = keyframes[0]?.world ?? replay.worlds.keys().next().value ?? 0;
  const assembler = new TimelineAssembler(durationMs, initialWorld, initialState);
  let keyframeIndex = 0;
  let world = initialWorld;
  for (const event of replay.events) {
    for (let keyframe = keyframes[keyframeIndex]; keyframe !== undefined && keyframe.timeMs <= event.timeMs; keyframe = keyframes[keyframeIndex]) {
      assembler.keyframe(keyframe);
      world = keyframe.world;
      keyframeIndex += 1;
    }
    world = assembler.event(event, world);
  }
  for (const keyframe of keyframes.slice(keyframeIndex)) assembler.keyframe(keyframe);
  return assembler.build(replay.header.targetEntityId, replay.header.targetUuid);
}
