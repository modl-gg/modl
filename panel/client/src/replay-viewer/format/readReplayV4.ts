import { AIR_NAME, type BlockState } from './BlockState.ts';
import { atLeast, parseVersion } from './MinecraftVersion.ts';
import { StatePalette } from './StatePalette.ts';
import { UNKNOWN_BLOCK, legacyBlockState } from './legacyBlockIds.ts';
import {
  type EquipmentSlotName,
  type InventorySlotData,
  type ItemStackData,
  type ParsedReplay,
  type PlayerInfoData,
  type ReplayEventData,
  REPLAY_MAGIC,
  ReplayFormatError,
  type ReplaySection,
} from './ReplayModel.ts';
import { MAX_BLOCK_STATES, MAX_EVENTS, MAX_SECTIONS } from './ReplayLimits.ts';
import { SectionBudget, type SectionStates, blockSectionKey, localIndex, requireBlock, statesHolding } from './WorldLayout.ts';

const MAX_SNAPSHOT_BLOCKS = 16_000_000;
const PLAYER_ID_BASE = 2_000_000_000;
const UTF8 = new TextDecoder('utf-8');

const V4_EQUIPMENT: Readonly<Record<number, EquipmentSlotName>> = { 0: 'mainhand', 1: 'offhand', 2: 'head', 3: 'chest', 4: 'legs', 5: 'feet' };
const V4_EFFECTS = ['', 'speed', 'slowness', 'haste', 'mining_fatigue', 'strength', 'instant_health', 'instant_damage', 'jump_boost', 'nausea', 'regeneration', 'resistance', 'fire_resistance', 'water_breathing', 'invisibility', 'blindness', 'night_vision', 'hunger', 'weakness', 'poison', 'wither', 'health_boost', 'absorption', 'saturation', 'glowing', 'levitation', 'luck', 'unluck', 'slow_falling', 'conduit_power', 'dolphins_grace', 'bad_omen', 'hero_of_the_village', 'darkness'];

class V4EntityIds {
  private readonly players = new Map<string, number>();
  private readonly recorded = new Map<number, number>();
  private readonly taken = new Set<number>();
  private next = PLAYER_ID_BASE;

  entity(raw: number): number {
    let id = this.recorded.get(raw);
    if (id === undefined) {
      id = this.taken.has(raw) ? this.fresh() : raw;
      this.taken.add(id);
      this.recorded.set(raw, id);
    }
    return id;
  }

  player(uuid: string): number {
    let id = this.players.get(uuid);
    if (id === undefined) {
      id = this.fresh();
      this.players.set(uuid, id);
    }
    return id;
  }

  playerOf(uuid: string): number | null {
    return this.players.get(uuid) ?? null;
  }

  private fresh(): number {
    while (this.taken.has(this.next)) this.next += 1;
    const id = this.next;
    this.next += 1;
    this.taken.add(id);
    return id;
  }
}

interface SectionBuilder {
  readonly x: number;
  readonly y: number;
  readonly z: number;
  states: SectionStates | null;
}

class Reader {
  private readonly view: DataView;
  private readonly bytes: Uint8Array;
  offset = 0;

  constructor(bytes: Uint8Array) {
    this.bytes = bytes;
    this.view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  }

  skipTo(end: number): void {
    if (this.offset > end || end > this.bytes.byteLength) throw new ReplayFormatError('v4 record length does not match its contents');
    this.offset = end;
  }

  get remaining(): number {
    return this.bytes.byteLength - this.offset;
  }

  private need(count: number): void {
    if (count < 0 || this.offset + count > this.bytes.byteLength) throw new ReplayFormatError('truncated v4 replay');
  }

  u8(): number {
    this.need(1);
    const value = this.view.getUint8(this.offset);
    this.offset += 1;
    return value;
  }

  i16(): number {
    this.need(2);
    const value = this.view.getInt16(this.offset);
    this.offset += 2;
    return value;
  }

  u16(): number {
    this.need(2);
    const value = this.view.getUint16(this.offset);
    this.offset += 2;
    return value;
  }

  i32(): number {
    this.need(4);
    const value = this.view.getInt32(this.offset);
    this.offset += 4;
    return value;
  }

  f32(): number {
    this.need(4);
    const value = this.view.getFloat32(this.offset);
    this.offset += 4;
    return value;
  }

  i64(): bigint {
    this.need(8);
    const value = this.view.getBigInt64(this.offset);
    this.offset += 8;
    return value;
  }

  bytesOf(length: number): Uint8Array<ArrayBuffer> {
    this.need(length);
    const value = this.bytes.slice(this.offset, this.offset + length);
    this.offset += length;
    return value;
  }

  string8(): string {
    return UTF8.decode(this.bytesOf(this.u8()));
  }

  string16(): string {
    return UTF8.decode(this.bytesOf(this.u16()));
  }

  uuid(): string {
    const hex = (value: bigint): string => (value < 0n ? value + (1n << 64n) : value).toString(16).padStart(16, '0');
    const text = hex(this.i64()) + hex(this.i64());
    return `${text.slice(0, 8)}-${text.slice(8, 12)}-${text.slice(12, 16)}-${text.slice(16, 20)}-${text.slice(20, 32)}`;
  }
}

function namespaced(name: string): string {
  const lower = name.trim().toLowerCase();
  return lower.includes(':') ? lower : `minecraft:${lower}`;
}

function itemStack(name: string, count: number, enchanted: boolean): ItemStackData | null {
  if (name === '' || name.toLowerCase() === 'air') return null;
  return { item: namespaced(name), count, enchanted, dyeColor: null, customName: null, damage: 0, maxDamage: 0 };
}

function inventoryMenuSlot(index: number): number | null {
  if (index >= 0 && index <= 8) return 36 + index;
  if (index >= 9 && index <= 35) return index;
  if (index >= 36 && index <= 39) return 8 - (index - 36);
  if (index === 40) return 45;
  return null;
}

function readEnchantments(reader: Reader, present: boolean): boolean {
  if (!present) return false;
  const count = reader.u8();
  for (let index = 0; index < count; index++) {
    reader.string8();
    reader.u8();
  }
  return count > 0;
}

function sectionOf(section: SectionBuilder): ReplaySection {
  return { x: section.x, y: section.y, z: section.z, states: section.states, skyLight: null, blockLight: null, biomes: null };
}

export function readReplayV4(bytes: Uint8Array): ParsedReplay {
  const reader = new Reader(bytes);
  for (const byte of REPLAY_MAGIC) {
    if (reader.u8() !== byte) throw new ReplayFormatError('not a modl replay');
  }
  const version = reader.u16();
  if (version < 1 || version > 4) throw new ReplayFormatError(`unsupported replay format ${String(version)}`);
  const hasEnchantments = version >= 3;
  const hasTlv = version >= 4;
  const readAngle = (): number => (hasTlv ? reader.f32() : (reader.i16() * 360) / 32767);
  const startEpochMs = Number(reader.i64());
  const minecraftVersion = reader.string16();
  reader.i32();
  reader.i32();
  reader.i32();
  const radiusBlocks = reader.i32();
  const parsedVersion = parseVersion(minecraftVersion);
  const legacyIds = !atLeast(parsedVersion, '1.13');
  const tall = atLeast(parsedVersion, '1.18');
  const minY = tall ? -64 : 0;
  const height = tall ? 384 : 256;
  const palette = new StatePalette([{ name: AIR_NAME, properties: {} }]);
  const stateFor = (globalId: number): number => {
    if (globalId === 0) return 0;
    const decoded: BlockState = legacyIds ? legacyBlockState(globalId) : { name: UNKNOWN_BLOCK, properties: {} };
    const index = palette.intern(decoded);
    if (palette.states.length > MAX_BLOCK_STATES) throw new ReplayFormatError('block palette too large');
    return index;
  };
  const sections = new Map<number, SectionBuilder>();
  const budget = new SectionBudget();
  const setBlock = (x: number, y: number, z: number, state: number): void => {
    requireBlock(x, y, z);
    const key = blockSectionKey(x, y, z);
    let section = sections.get(key);
    if (section === undefined) {
      if (sections.size >= MAX_SECTIONS) throw new ReplayFormatError('too many sections');
      section = { x: x >> 4, y: y >> 4, z: z >> 4, states: null };
      sections.set(key, section);
    }
    const states = statesHolding(section.states, state);
    if (states !== section.states) budget.charge(states.byteLength);
    states[localIndex(x, y, z)] = state;
    section.states = states;
  };
  const blockCount = reader.i32();
  if (blockCount < 0 || blockCount > MAX_SNAPSHOT_BLOCKS) throw new ReplayFormatError('invalid snapshot size');
  for (let index = 0; index < blockCount; index++) {
    const x = reader.i32();
    const y = reader.i16();
    const z = reader.i32();
    const state = stateFor(reader.i32());
    if (state !== 0) setBlock(x, y, z, state);
  }
  const events: ReplayEventData[] = [];
  const ids = new V4EntityIds();
  const infos = new Map<string, PlayerInfoData>();
  let targetUuid: string | null = null;
  const playerId = (uuid: string): number => ids.player(uuid);
  const sneaking = new Map<number, boolean>();
  while (reader.remaining > 0) {
    const type = reader.u8();
    const timeMs = Math.max(0, reader.i32());
    const length = hasTlv ? reader.u16() : 0;
    const start = reader.offset;
    switch (type) {
      case 0x01: {
        const x = reader.i32();
        const y = reader.i16();
        const z = reader.i32();
        requireBlock(x, y, z);
        events.push({ kind: 'block', timeMs, x, y, z, state: stateFor(reader.i32()) });
        break;
      }
      case 0x02: {
        const entityId = ids.entity(reader.i32());
        reader.i16();
        const x = reader.f32();
        const y = reader.f32();
        const z = reader.f32();
        const typeName = UTF8.decode(reader.bytesOf(reader.u16()));
        events.push({
          kind: 'spawn',
          timeMs,
          entity: { entityId, type: namespaced(typeName === '' ? 'unknown' : typeName), uuid: null, x, y, z, yaw: 0, pitch: 0, headYaw: 0, onGround: true, metadata: {}, equipment: [], effects: [], vehicle: null },
        });
        break;
      }
      case 0x03: {
        const entityId = ids.entity(reader.i32());
        const x = reader.f32();
        const y = reader.f32();
        const z = reader.f32();
        const yaw = readAngle();
        const pitch = readAngle();
        events.push({ kind: 'teleport', timeMs, entityId, x, y, z, yaw, pitch, onGround: true });
        break;
      }
      case 0x04:
        events.push({ kind: 'remove', timeMs, entityIds: [ids.entity(reader.i32())] });
        break;
      case 0x05: {
        const uuid = reader.uuid();
        const name = reader.string8();
        const x = reader.f32();
        const y = reader.f32();
        const z = reader.f32();
        const yaw = readAngle();
        const pitch = readAngle();
        reader.bytesOf(reader.u16());
        const info: PlayerInfoData = { uuid, name, skinHash: null, slim: false, gameMode: null, skinPng: infos.get(uuid)?.skinPng ?? null };
        infos.set(uuid, info);
        events.push({ kind: 'player_info', timeMs, player: info });
        events.push({
          kind: 'spawn',
          timeMs,
          entity: { entityId: playerId(uuid), type: 'minecraft:player', uuid, x, y, z, yaw, pitch, headYaw: yaw, onGround: true, metadata: {}, equipment: [], effects: [], vehicle: null },
        });
        break;
      }
      case 0x06: {
        const uuid = reader.uuid();
        const x = reader.f32();
        const y = reader.f32();
        const z = reader.f32();
        const yaw = readAngle();
        const pitch = readAngle();
        events.push({ kind: 'teleport', timeMs, entityId: playerId(uuid), x, y, z, yaw, pitch, onGround: true });
        break;
      }
      case 0x07:
        events.push({ kind: 'remove', timeMs, entityIds: [playerId(reader.uuid())] });
        break;
      case 0x08: {
        const entityId = playerId(reader.uuid());
        const animation = reader.u8();
        if (animation === 0) events.push({ kind: 'animation', timeMs, entityId, animation: 'swing_main' });
        else if (animation === 1) events.push({ kind: 'animation', timeMs, entityId, animation: 'swing_off' });
        else if (animation === 2 || animation === 3) {
          sneaking.set(entityId, animation === 2);
          events.push({ kind: 'metadata', timeMs, entityId, metadata: { flags: { onFire: false, sneaking: animation === 2, sprinting: false, swimming: false, invisible: false, glowing: false, gliding: false } } });
        } else if (animation === 4) events.push({ kind: 'hurt', timeMs, entityId });
        break;
      }
      case 0x09: {
        const uuid = reader.uuid();
        events.push({ kind: 'chat', timeMs, chatKind: 'system', json: reader.string16(), senderUuid: uuid });
        break;
      }
      case 0x0a: {
        const entityId = playerId(reader.uuid());
        events.push({ kind: 'equipment', timeMs, entityId, equipment: [{ slot: 'mainhand', item: itemStack(reader.string8(), 1, false) }] });
        break;
      }
      case 0x0b: {
        const entityId = playerId(reader.uuid());
        const count = reader.u8();
        const equipment = [];
        for (let index = 0; index < count; index++) {
          const slot = V4_EQUIPMENT[reader.u8()];
          const name = reader.string8();
          const enchanted = readEnchantments(reader, hasEnchantments);
          if (slot !== undefined) equipment.push({ slot, item: itemStack(name, 1, enchanted) });
        }
        events.push({ kind: 'equipment', timeMs, entityId, equipment });
        break;
      }
      case 0x0c: {
        const uuid = reader.uuid();
        targetUuid ??= uuid;
        const health = reader.f32();
        const food = reader.u8();
        const saturation = reader.f32();
        events.push({ kind: 'target_health', timeMs, health, food, saturation });
        break;
      }
      case 0x0d: {
        const entityId = playerId(reader.uuid());
        const count = reader.u8();
        for (let index = 0; index < count; index++) {
          const effect = V4_EFFECTS[reader.u8()] ?? '';
          const amplifier = reader.u8();
          const durationTicks = reader.i32();
          if (effect !== '') events.push({ kind: 'effect', timeMs, entityId, effect: { effect: `minecraft:${effect}`, amplifier, durationTicks } });
        }
        break;
      }
      case 0x0e: {
        const uuid = reader.uuid();
        targetUuid ??= uuid;
        const replaceAll = reader.u8() !== 0;
        const count = reader.u8();
        const slots: InventorySlotData[] = [];
        for (let index = 0; index < count; index++) {
          const slotIndex = reader.u8();
          const name = reader.string16();
          const amount = reader.u8();
          const enchanted = readEnchantments(reader, hasEnchantments);
          const slot = inventoryMenuSlot(slotIndex);
          if (slot !== null) slots.push({ slot, item: itemStack(name, amount, enchanted) });
        }
        events.push({ kind: 'target_inventory', timeMs, replaceAll, slots });
        break;
      }
      case 0x0f: {
        const uuid = reader.uuid();
        const png = reader.bytesOf(reader.i32());
        const previous = infos.get(uuid);
        const info: PlayerInfoData = { uuid, name: previous?.name ?? '', skinHash: null, slim: false, gameMode: null, skinPng: png };
        infos.set(uuid, info);
        events.push({ kind: 'player_info', timeMs, player: info });
        break;
      }
      case 0x10:
      case 0x11:
        reader.uuid();
        reader.i32();
        reader.i16();
        reader.i32();
        reader.i32();
        break;
      default:
        if (!hasTlv) {
          reader.offset = bytes.byteLength;
          break;
        }
        break;
    }
    if (hasTlv) reader.skipTo(start + length);
    if (events.length > MAX_EVENTS) throw new ReplayFormatError('too many events');
  }
  events.sort((left, right) => left.timeMs - right.timeMs);
  const dimension = { world: 0, key: 'minecraft:overworld', minY, height, hasSkyLight: true };
  const durationMs = events.at(-1)?.timeMs ?? 0;
  const targetEntityId = targetUuid === null ? null : ids.playerOf(targetUuid);
  return {
    header: {
      formatVersion: version,
      minecraftVersion,
      dimension: dimension.key,
      minY,
      height,
      startEpochMs,
      durationMs,
      targetUuid,
      targetName: targetUuid === null ? null : infos.get(targetUuid)?.name ?? null,
      markerMs: null,
      targetEntityId,
      hardcore: false,
      lightRecorded: false,
      radiusBlocks,
      truncated: false,
    },
    palette: palette.states,
    biomeKeys: [],
    worlds: new Map([[0, { dimension, sections: [...sections.values()].map(sectionOf), blockEntities: [] }]]),
    keyframes: [],
    events,
  };
}
