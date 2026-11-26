import { fromBinary } from '@bufbuild/protobuf';
import {
  type ReplayBlockEntity,
  type ReplayDimension,
  type ReplayEffect,
  type ReplayEntityMetadata,
  type ReplayEntitySnapshot,
  type ReplayEquipment,
  type ReplayEvent,
  type ReplayFrame,
  type ReplayHeader as ProtoHeader,
  type ReplayInventorySlot,
  type ReplayItemStack,
  type ReplayKeyframe,
  type ReplayPlayerInfo,
  type ReplayPosition,
  type ReplayTargetState,
  ReplayAnimation,
  ReplayChatKind,
  ReplayEquipmentSlot,
  ReplayFrameSchema,
  ReplayGameMode,
  ReplayPose,
} from '../../../../../proto/gen/ts/modl/v1/replay_format_pb.ts';
import { AIR_NAME, type BlockState } from './BlockState.ts';
import { bareName } from './ids.ts';
import {
  type AnimationName,
  type BlockEntityData,
  DEFAULT_DIMENSION,
  type DimensionInfo,
  type EffectData,
  type EntityMetadataData,
  type EntitySnapshotData,
  type EquipmentData,
  type EquipmentSlotName,
  type GameModeName,
  type InventorySlotData,
  type ItemStackData,
  type KeyframeData,
  type ParsedReplay,
  type PlayerInfoData,
  type PoseName,
  type ReplayEventData,
  ReplayFormatError,
  type ReplaySection,
  type ReplayWorld,
  type TargetStateData,
} from './ReplayModel.ts';
import { MAX_BLOCK_ENTITIES, MAX_BLOCK_STATES, MAX_EVENTS, MAX_FRAME_BYTES, MAX_KEYFRAMES, MAX_SECTIONS, MAX_STRINGS } from './ReplayLimits.ts';
import { BIOME_CELLS, LIGHT_BYTES, SECTION_VOLUME, SectionBudget, createStates, requireBlock, requireSection } from './WorldLayout.ts';

const FIXED_POINT = 4096;
const SIGN_LINES = 4;
const MAX_SIGN_LINE_LENGTH = 512;
const MAX_SKIN_HASH_LENGTH = 64;

class StringTable {
  readonly values: string[] = [];

  add(values: readonly string[]): void {
    if (this.values.length + values.length > MAX_STRINGS) throw new ReplayFormatError('string table too large');
    for (const value of values) this.values.push(value);
  }

  get(index: number): string {
    const value = this.values[index];
    if (value === undefined) throw new ReplayFormatError(`string index ${String(index)} out of range`);
    return value;
  }
}

function signLines(lines: readonly string[]): string[] {
  return lines.slice(0, SIGN_LINES).map((line) => line.slice(0, MAX_SIGN_LINE_LENGTH));
}

interface WorldBuilder {
  dimension: DimensionInfo;
  readonly sections: ReplaySection[];
  readonly blockEntities: BlockEntityData[];
}

interface SectionFrame {
  readonly world: number;
  readonly x: number;
  readonly y: number;
  readonly z: number;
  readonly states: readonly number[];
  readonly skyLight: Uint8Array;
  readonly blockLight: Uint8Array;
  readonly biomes: readonly number[];
}

function readVarint(bytes: Uint8Array, offset: number): [number, number] {
  let value = 0;
  let shift = 0;
  let cursor = offset;
  for (;;) {
    if (cursor >= bytes.length) throw new ReplayFormatError('truncated frame length');
    const byte = bytes[cursor] ?? 0;
    cursor += 1;
    value += (byte & 0x7f) * 2 ** shift;
    if ((byte & 0x80) === 0) break;
    shift += 7;
    if (shift > 35) throw new ReplayFormatError('frame length too large');
  }
  return [value, cursor];
}

function fixed(value: bigint): number {
  return Number(value) / FIXED_POINT;
}

function position(value: ReplayPosition | undefined): [number, number, number] {
  if (value === undefined) return [0, 0, 0];
  return [fixed(value.x), fixed(value.y), fixed(value.z)];
}

function gameMode(value: ReplayGameMode): GameModeName | null {
  switch (value) {
    case ReplayGameMode.SURVIVAL:
      return 'survival';
    case ReplayGameMode.CREATIVE:
      return 'creative';
    case ReplayGameMode.ADVENTURE:
      return 'adventure';
    case ReplayGameMode.SPECTATOR:
      return 'spectator';
    case ReplayGameMode.UNSPECIFIED:
      return null;
  }
}

function pose(value: ReplayPose): PoseName | null {
  switch (value) {
    case ReplayPose.STANDING:
      return 'standing';
    case ReplayPose.FALL_FLYING:
      return 'fall_flying';
    case ReplayPose.SLEEPING:
      return 'sleeping';
    case ReplayPose.SWIMMING:
      return 'swimming';
    case ReplayPose.SPIN_ATTACK:
      return 'spin_attack';
    case ReplayPose.CROUCHING:
      return 'crouching';
    case ReplayPose.LONG_JUMPING:
      return 'long_jumping';
    case ReplayPose.DYING:
      return 'dying';
    case ReplayPose.SITTING:
      return 'sitting';
    case ReplayPose.UNSPECIFIED:
      return null;
  }
}

function equipmentSlot(value: ReplayEquipmentSlot): EquipmentSlotName | null {
  switch (value) {
    case ReplayEquipmentSlot.MAIN_HAND:
      return 'mainhand';
    case ReplayEquipmentSlot.OFF_HAND:
      return 'offhand';
    case ReplayEquipmentSlot.HEAD:
      return 'head';
    case ReplayEquipmentSlot.CHEST:
      return 'chest';
    case ReplayEquipmentSlot.LEGS:
      return 'legs';
    case ReplayEquipmentSlot.FEET:
      return 'feet';
    case ReplayEquipmentSlot.BODY:
      return 'body';
    case ReplayEquipmentSlot.SADDLE:
      return 'saddle';
    case ReplayEquipmentSlot.UNSPECIFIED:
      return null;
  }
}

function animation(value: ReplayAnimation): AnimationName | null {
  switch (value) {
    case ReplayAnimation.SWING_MAIN_HAND:
      return 'swing_main';
    case ReplayAnimation.SWING_OFF_HAND:
      return 'swing_off';
    case ReplayAnimation.CRITICAL_HIT:
      return 'critical';
    case ReplayAnimation.MAGIC_CRITICAL_HIT:
      return 'magic_critical';
    case ReplayAnimation.WAKE_UP:
      return 'wake_up';
    case ReplayAnimation.UNSPECIFIED:
      return null;
  }
}

function chatKind(value: ReplayChatKind): 'system' | 'player' | 'action_bar' {
  switch (value) {
    case ReplayChatKind.PLAYER:
      return 'player';
    case ReplayChatKind.ACTION_BAR:
      return 'action_bar';
    case ReplayChatKind.SYSTEM:
    case ReplayChatKind.UNSPECIFIED:
      return 'system';
  }
}

class FrameDecoder {
  readonly strings = new StringTable();
  readonly palette: BlockState[] = [];
  readonly worlds = new Map<number, WorldBuilder>();
  readonly keyframes: KeyframeData[] = [];
  readonly events: ReplayEventData[] = [];
  header: ProtoHeader | null = null;
  durationMs: number | null = null;
  truncated = false;
  private lastBatchStart = 0;
  private sectionCount = 0;
  private readonly budget = new SectionBudget();
  private blockEntityCount = 0;
  private eventCount = 0;

  addPalette(states: readonly { name: string; properties: readonly { name: string; value: string }[] }[]): void {
    if (this.palette.length + states.length > MAX_BLOCK_STATES) throw new ReplayFormatError('block palette too large');
    for (const state of states) {
      const properties: Record<string, string> = {};
      for (const property of state.properties) properties[property.name] = property.value;
      const decoded: BlockState = { name: bareName(state.name), properties };
      if (this.palette.length === 0 && decoded.name !== AIR_NAME) throw new ReplayFormatError('the block palette does not start with air');
      this.palette.push(decoded);
    }
  }

  private stateIndex(value: number): number {
    if (value >= this.palette.length) throw new ReplayFormatError(`block state ${String(value)} out of range`);
    return value;
  }

  dimension(value: ReplayDimension | undefined): DimensionInfo {
    if (value === undefined) return { ...DEFAULT_DIMENSION, world: 0 };
    if (value.height === 0 || value.height > 4096 || value.height % 16 !== 0) throw new ReplayFormatError('invalid dimension height');
    return { world: value.world, key: value.key === '' ? DEFAULT_DIMENSION.key : value.key, minY: value.minY, height: value.height, hasSkyLight: value.hasSkyLight };
  }

  private world(index: number): WorldBuilder {
    let world = this.worlds.get(index);
    if (world === undefined) {
      const base = this.header?.dimension;
      world = { dimension: { ...this.dimension(base), world: index }, sections: [], blockEntities: [] };
      this.worlds.set(index, world);
    }
    return world;
  }

  section(value: SectionFrame): void {
    if (value.states.length !== SECTION_VOLUME && value.states.length !== 0) throw new ReplayFormatError('section without 4096 states');
    requireSection(value.x, value.y, value.z);
    if (++this.sectionCount > MAX_SECTIONS) throw new ReplayFormatError('too many sections');
    const states = this.sectionStates(value.states);
    const skyLight = value.skyLight.length === LIGHT_BYTES ? value.skyLight.slice() : null;
    const blockLight = value.blockLight.length === LIGHT_BYTES ? value.blockLight.slice() : null;
    const biomes = this.sectionBiomes(value.biomes);
    if (states === null && skyLight === null && blockLight === null && biomes === null) return;
    this.budget.charge((states?.byteLength ?? 0) + (skyLight?.byteLength ?? 0) + (blockLight?.byteLength ?? 0) + (biomes?.byteLength ?? 0));
    this.world(value.world).sections.push({ x: value.x, y: value.y, z: value.z, states, skyLight, blockLight, biomes });
  }

  private sectionStates(values: readonly number[]): ReplaySection['states'] {
    let largest = 0;
    for (const value of values) {
      if (value > largest) largest = value;
    }
    if (largest === 0) return null;
    this.stateIndex(largest);
    const states = createStates(largest);
    states.set(values);
    return states;
  }

  private sectionBiomes(values: readonly number[]): Uint32Array<ArrayBuffer> | null {
    if (values.length !== BIOME_CELLS) return null;
    const biomes = Uint32Array.from(values);
    for (const biome of biomes) {
      if (biome >= this.strings.values.length) throw new ReplayFormatError('biome index out of range');
    }
    return biomes;
  }

  blockEntity(value: ReplayBlockEntity): void {
    if (++this.blockEntityCount > MAX_BLOCK_ENTITIES) throw new ReplayFormatError('too many block entities');
    requireBlock(value.x, value.y, value.z);
    this.world(value.world).blockEntities.push({
      x: value.x,
      y: value.y,
      z: value.z,
      type: this.strings.get(value.type),
      frontText: signLines(value.frontText),
      backText: signLines(value.backText),
      skinHash: value.skinHash === '' ? null : value.skinHash.slice(0, MAX_SKIN_HASH_LENGTH),
    });
  }

  item(value: ReplayItemStack | undefined): ItemStackData | null {
    if (value === undefined) return null;
    return {
      item: this.strings.get(value.item),
      count: value.count,
      enchanted: value.enchanted,
      dyeColor: value.dyeColor ?? null,
      customName: value.customName === '' ? null : value.customName,
      damage: value.damage,
      maxDamage: value.maxDamage,
    };
  }

  metadata(value: ReplayEntityMetadata | undefined): EntityMetadataData {
    if (value === undefined) return {};
    const flags = value.flags;
    const decodedPose = value.pose === undefined ? null : pose(value.pose);
    return {
      ...(flags === undefined ? {} : {
        flags: {
          onFire: flags.onFire,
          sneaking: flags.sneaking,
          sprinting: flags.sprinting,
          swimming: flags.swimming,
          invisible: flags.invisible,
          glowing: flags.glowing,
          gliding: flags.gliding,
        },
      }),
      ...(decodedPose === null ? {} : { pose: decodedPose }),
      ...(value.item === undefined ? {} : { item: this.item(value.item) }),
      ...(value.customName === undefined ? {} : { customName: value.customName }),
      ...(value.customNameVisible === undefined ? {} : { customNameVisible: value.customNameVisible }),
      ...(value.baby === undefined ? {} : { baby: value.baby }),
      ...(value.health === undefined ? {} : { health: value.health }),
      ...(value.blockState === undefined ? {} : { blockState: this.stateIndex(value.blockState) }),
      ...(value.skinParts === undefined ? {} : { skinParts: value.skinParts }),
      ...(value.leftHanded === undefined ? {} : { leftHanded: value.leftHanded }),
      ...(value.variant === undefined ? {} : { variant: value.variant }),
    };
  }

  equipment(values: readonly ReplayEquipment[]): EquipmentData[] {
    const result: EquipmentData[] = [];
    for (const value of values) {
      const slot = equipmentSlot(value.slot);
      if (slot !== null) result.push({ slot, item: this.item(value.item) });
    }
    return result;
  }

  effect(value: ReplayEffect | undefined): EffectData | null {
    if (value === undefined) return null;
    return { effect: this.strings.get(value.effect), amplifier: value.amplifier, durationTicks: value.durationTicks };
  }

  entity(value: ReplayEntitySnapshot): EntitySnapshotData {
    const [x, y, z] = position(value.position);
    return {
      entityId: value.entityId,
      type: this.strings.get(value.type),
      uuid: value.uuid === '' ? null : value.uuid,
      x,
      y,
      z,
      yaw: value.yaw,
      pitch: value.pitch,
      headYaw: value.headYaw,
      onGround: value.onGround,
      metadata: this.metadata(value.metadata),
      equipment: this.equipment(value.equipment),
      effects: value.effects.map((effect) => this.effect(effect)).filter((effect) => effect !== null),
      vehicle: value.vehicle ?? null,
    };
  }

  player(value: ReplayPlayerInfo): PlayerInfoData {
    return {
      uuid: value.uuid,
      name: value.name,
      skinHash: value.skinHash === '' ? null : value.skinHash,
      slim: value.slim,
      gameMode: gameMode(value.gameMode),
      skinPng: null,
    };
  }

  slots(values: readonly ReplayInventorySlot[]): InventorySlotData[] {
    return values.map((value) => ({ slot: value.slot, item: this.item(value.item) }));
  }

  target(value: ReplayTargetState | undefined): TargetStateData | null {
    if (value === undefined) return null;
    return {
      health: value.health,
      maxHealth: value.maxHealth,
      absorption: value.absorption,
      food: value.food,
      saturation: value.saturation,
      armor: value.armor,
      xpLevel: value.xpLevel,
      xpProgress: value.xpProgress,
      selectedSlot: value.selectedSlot,
      gameMode: gameMode(value.gameMode),
      inventory: this.slots(value.inventory),
    };
  }

  keyframe(value: ReplayKeyframe): void {
    if (this.keyframes.length >= MAX_KEYFRAMES) throw new ReplayFormatError('too many keyframes');
    this.keyframes.push({
      timeMs: value.timeMs,
      world: value.world,
      players: value.players.map((player) => this.player(player)),
      entities: value.entities.map((entity) => this.entity(entity)),
      target: this.target(value.target),
      timeOfDay: Number(value.timeOfDay),
      rain: value.rain,
      thunder: value.thunder,
    });
  }

  batch(startMs: number, events: readonly ReplayEvent[]): void {
    if (startMs < this.lastBatchStart) throw new ReplayFormatError('event batches out of order');
    let time = startMs;
    for (const event of events) {
      if (++this.eventCount > MAX_EVENTS) throw new ReplayFormatError('too many events');
      time += event.deltaMs;
      const decoded = this.event(time, event);
      if (decoded !== null) this.events.push(decoded);
    }
    this.lastBatchStart = time;
  }

  private event(timeMs: number, event: ReplayEvent): ReplayEventData | null {
    const value = event.event;
    switch (value.case) {
      case 'entitySpawn':
        return value.value.entity === undefined ? null : { kind: 'spawn', timeMs, entity: this.entity(value.value.entity) };
      case 'entityRemove':
        return { kind: 'remove', timeMs, entityIds: value.value.entityIds };
      case 'entityMove': {
        const move = value.value;
        return {
          kind: 'move', timeMs, entityId: move.entityId, dx: move.dx / FIXED_POINT, dy: move.dy / FIXED_POINT, dz: move.dz / FIXED_POINT,
          yaw: move.yaw ?? null, pitch: move.pitch ?? null, headYaw: move.headYaw ?? null, onGround: move.onGround,
        };
      }
      case 'entityTeleport': {
        const [x, y, z] = position(value.value.position);
        return { kind: 'teleport', timeMs, entityId: value.value.entityId, x, y, z, yaw: value.value.yaw, pitch: value.value.pitch, onGround: value.value.onGround };
      }
      case 'entityMetadata':
        return { kind: 'metadata', timeMs, entityId: value.value.entityId, metadata: this.metadata(value.value.metadata) };
      case 'entityEquipment':
        return { kind: 'equipment', timeMs, entityId: value.value.entityId, equipment: this.equipment(value.value.equipment) };
      case 'entityAnimation': {
        const name = animation(value.value.animation);
        return name === null ? null : { kind: 'animation', timeMs, entityId: value.value.entityId, animation: name };
      }
      case 'entityHurt':
        return { kind: 'hurt', timeMs, entityId: value.value.entityId };
      case 'entityDeath':
        return { kind: 'death', timeMs, entityId: value.value.entityId };
      case 'entityEffect': {
        const effect = this.effect(value.value.effect);
        return effect === null ? null : { kind: 'effect', timeMs, entityId: value.value.entityId, effect };
      }
      case 'entityEffectRemove':
        return { kind: 'effect_remove', timeMs, entityId: value.value.entityId, effect: this.strings.get(value.value.effect) };
      case 'entityPassengers':
        return { kind: 'passengers', timeMs, vehicle: value.value.vehicle, passengers: value.value.passengers };
      case 'itemPickup':
        return { kind: 'pickup', timeMs, collected: value.value.collected, collector: value.value.collector };
      case 'blockChange': {
        const change = value.value;
        requireBlock(change.x, change.y, change.z);
        return { kind: 'block', timeMs, x: change.x, y: change.y, z: change.z, state: this.stateIndex(change.state) };
      }
      case 'blockBreakProgress':
        return { kind: 'break_progress', timeMs, entityId: value.value.entityId, x: value.value.x, y: value.value.y, z: value.value.z, stage: value.value.stage };
      case 'playerInfo':
        return value.value.player === undefined ? null : { kind: 'player_info', timeMs, player: this.player(value.value.player) };
      case 'playerInfoRemove':
        return { kind: 'player_remove', timeMs, uuids: value.value.uuids };
      case 'chat':
        return { kind: 'chat', timeMs, chatKind: chatKind(value.value.kind), json: value.value.componentJson, senderUuid: value.value.senderUuid === '' ? null : value.value.senderUuid };
      case 'targetHealth':
        return { kind: 'target_health', timeMs, health: value.value.health, food: value.value.food, saturation: value.value.saturation };
      case 'targetAttributes':
        return { kind: 'target_attributes', timeMs, maxHealth: value.value.maxHealth ?? null, absorption: value.value.absorption ?? null, armor: value.value.armor ?? null };
      case 'targetExperience':
        return { kind: 'target_experience', timeMs, level: value.value.level, progress: value.value.progress };
      case 'targetSelectedSlot':
        return { kind: 'target_slot', timeMs, slot: value.value.slot };
      case 'targetInventory':
        return { kind: 'target_inventory', timeMs, replaceAll: value.value.replaceAll, slots: this.slots(value.value.slots) };
      case 'targetGameMode':
        return { kind: 'target_gamemode', timeMs, gameMode: gameMode(value.value.gameMode) };
      case 'dimensionChange': {
        const dimension = this.dimension(value.value.dimension);
        this.world(dimension.world).dimension = dimension;
        return { kind: 'dimension', timeMs, dimension };
      }
      case 'worldTime':
        return { kind: 'time', timeMs, timeOfDay: Number(value.value.timeOfDay) };
      case 'weather':
        return { kind: 'weather', timeMs, rain: value.value.rain, thunder: value.value.thunder };
      case 'blockEntity':
        this.blockEntity(value.value);
        return null;
      case undefined:
        return null;
    }
  }
}

function decodeFrame(bytes: Uint8Array): ReplayFrame['frame'] {
  try {
    return fromBinary(ReplayFrameSchema, bytes).frame;
  } catch {
    throw new ReplayFormatError('malformed frame');
  }
}

export function decodeFrames(bytes: Uint8Array): ParsedReplay {
  const decoder = new FrameDecoder();
  let offset = 0;
  let first = true;
  while (offset < bytes.length) {
    const [length, start] = readVarint(bytes, offset);
    if (length > MAX_FRAME_BYTES || start + length > bytes.length) throw new ReplayFormatError('truncated frame');
    const frame = decodeFrame(bytes.subarray(start, start + length));
    offset = start + length;
    if (first && frame.case !== 'header') throw new ReplayFormatError('the first frame is not a header');
    first = false;
    switch (frame.case) {
      case 'header':
        if (decoder.header !== null) throw new ReplayFormatError('duplicate header');
        decoder.header = frame.value;
        break;
      case 'strings':
        decoder.strings.add(frame.value.values);
        break;
      case 'blockPalette':
        decoder.addPalette(frame.value.states);
        break;
      case 'section':
        decoder.section(frame.value);
        break;
      case 'blockEntity':
        decoder.blockEntity(frame.value);
        break;
      case 'keyframe':
        decoder.keyframe(frame.value);
        break;
      case 'events':
        decoder.batch(frame.value.startMs, frame.value.events);
        break;
      case 'end':
        decoder.durationMs = frame.value.durationMs;
        decoder.truncated = frame.value.truncated;
        break;
      case undefined:
        break;
    }
  }
  const header = decoder.header;
  if (header === null) throw new ReplayFormatError('missing header');
  const dimension = decoder.dimension(header.dimension);
  const worlds = new Map<number, ReplayWorld>();
  for (const [index, world] of decoder.worlds) worlds.set(index, world);
  if (!worlds.has(dimension.world)) worlds.set(dimension.world, { dimension, sections: [], blockEntities: [] });
  const lastEvent = decoder.events.at(-1)?.timeMs ?? 0;
  const durationMs = decoder.durationMs ?? Math.max(header.durationMs, lastEvent);
  return {
    header: {
      formatVersion: header.formatVersion,
      minecraftVersion: header.minecraftVersion,
      dimension: dimension.key,
      minY: dimension.minY,
      height: dimension.height,
      startEpochMs: Number(header.windowStartEpochMs),
      durationMs,
      targetUuid: header.targetUuid === '' ? null : header.targetUuid,
      targetName: header.targetName === '' ? null : header.targetName,
      markerMs: null,
      targetEntityId: header.targetEntityId === 0 ? null : header.targetEntityId,
      hardcore: header.hardcore,
      lightRecorded: header.lightRecorded,
      radiusBlocks: header.radiusBlocks,
      truncated: decoder.truncated || decoder.durationMs === null,
    },
    palette: decoder.palette.length === 0 ? [{ name: AIR_NAME, properties: {} }] : decoder.palette,
    biomeKeys: decoder.strings.values,
    worlds,
    keyframes: decoder.keyframes,
    events: decoder.events,
  };
}
