import i18n from '@/lib/i18n';
import { optionalField } from '@/lib/defined-fields';
import { type ParsedReplay, type ReplayEvent, EventType, AnimationType, type PlayerAnimEvent, type PlayerSpawnEvent, type PlayerMoveEvent, type EntitySpawnEvent, type EntityMoveEvent } from '../types/ReplayFile';
import type { PlaybackState, PlaybackRate } from '../types/PlaybackState';
import { WorldState, type BlockChange } from './WorldState';
import { interpolatePosition, lerpAngle, binarySearchTime } from './Interpolation';

export type EngineListener = (state: PlaybackState, changes: BlockChange[]) => void;

const UUID_PATTERN = /^[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}$/i;
const textDecoder = new TextDecoder();

function appendToGroup<K, V>(groups: Map<K, V[]>, key: K, value: V): void {
  const group = groups.get(key);
  if (group) {
    group.push(value);
  } else {
    groups.set(key, [value]);
  }
}

function decodeSkinUuid(equipment: Uint8Array<ArrayBuffer>, fallbackUuid: string): string {
  if (equipment.length === 0) return fallbackUuid;
  const decoded = textDecoder.decode(equipment);
  return UUID_PATTERN.test(decoded) ? decoded : fallbackUuid;
}

interface EntityState {
  entityId: number;
  entityTypeId: number;
  entityTypeName: string;
  x: number;
  y: number;
  z: number;
  yaw: number;
  pitch: number;
  alive: boolean;
}

export interface TrackedPlayer {
  uuid: string;
  playerName: string;
  x: number;
  y: number;
  z: number;
  yaw: number;
  pitch: number;
  alive: boolean;
  sneaking: boolean;
  lastSwingTime: number;
  lastDamageTime: number;
  skinUuid: string;
  heldItem: string;
  equipment: Map<number, { itemName: string; enchantments?: { id: string; level: number }[] }>;
  health: number;
  food: number;
  saturation: number;
  effects: { effectTypeId: number; amplifier: number; durationTicks: number; appliedAtMs: number }[];
  inventory: Map<number, { itemName: string; count: number; enchantments?: { id: string; level: number }[] }>;
}

export class ReplayEngine {
  private readonly replay: ParsedReplay;
  private readonly worldState: WorldState;
  private playing = false;
  private currentTimeMs = 0;
  private readonly totalDurationMs: number;
  private rate: PlaybackRate = 1;
  private followingPlayer: string | null = null;

  private readonly listeners = new Set<EngineListener>();
  private animationFrameId: number | null = null;
  private lastFrameTime: number | null = null;

  // Entity/player tracking
  private readonly entities = new Map<number, EntityState>();
  private readonly players = new Map<string, TrackedPlayer>();
  // Embedded skin PNG data: uuid → Uint8Array
  private readonly playerSkins = new Map<string, Uint8Array<ArrayBuffer>>();

  // Per-entity/player move event indices for interpolation
  private readonly playerMoveEvents = new Map<string, (PlayerSpawnEvent | PlayerMoveEvent)[]>();
  private readonly entityMoveEvents = new Map<number, (EntitySpawnEvent | EntityMoveEvent)[]>();

  private currentEventIndex = 0;

  constructor(replay: ParsedReplay) {
    this.replay = replay;
    this.worldState = new WorldState(replay);

    this.totalDurationMs = replay.events.at(-1)?.timestampDeltaMs ?? 0;

    // Pre-index move events per entity/player
    this.buildMoveIndices();
  }

  private buildMoveIndices(): void {
    for (const event of this.replay.events) {
      if (event.type === EventType.PLAYER_SPAWN || event.type === EventType.PLAYER_MOVE) {
        appendToGroup(this.playerMoveEvents, event.uuid, event);
      }
      if (event.type === EventType.ENTITY_SPAWN || event.type === EventType.ENTITY_MOVE) {
        appendToGroup(this.entityMoveEvents, event.entityId, event);
      }
    }
  }

  subscribe(listener: EngineListener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  play(): void {
    if (this.playing) return;
    this.playing = true;
    this.lastFrameTime = null;
    this.animationFrameId = requestAnimationFrame(this.tick);
    this.notifyListeners([]);
  }

  pause(): void {
    this.playing = false;
    if (this.animationFrameId !== null) {
      cancelAnimationFrame(this.animationFrameId);
      this.animationFrameId = null;
    }
    this.lastFrameTime = null;
    this.notifyListeners([]);
  }

  seek(timeMs: number): void {
    this.currentTimeMs = Math.max(0, Math.min(timeMs, this.totalDurationMs));
    this.currentEventIndex = 0; // Reset - worldState handles seeking internally
    this.rebuildEntityState();
    const changes = this.worldState.advanceTo(this.currentTimeMs);
    this.notifyListeners(changes);
  }

  setRate(rate: PlaybackRate): void {
    this.rate = rate;
    this.notifyListeners([]);
  }

  followPlayer(uuid: string | null): void {
    this.followingPlayer = uuid;
    this.notifyListeners([]);
  }

  getState(): PlaybackState {
    return {
      playing: this.playing,
      currentTimeMs: this.currentTimeMs,
      totalDurationMs: this.totalDurationMs,
      rate: this.rate,
      followingPlayer: this.followingPlayer,
    };
  }

  getPlayers(): Map<string, TrackedPlayer> {
    return new Map(this.players);
  }

  getPlayerSkin(uuid: string): Uint8Array<ArrayBuffer> | undefined {
    return this.playerSkins.get(uuid);
  }

  getEntities(): Map<number, EntityState> {
    return new Map(this.entities);
  }

  getWorldState(): WorldState {
    return this.worldState;
  }

  /** Get interpolated entity position at current time */
  getInterpolatedEntityPosition(entityId: number): { x: number; y: number; z: number; yaw: number; pitch: number } | null {
    const moveEvents = this.entityMoveEvents.get(entityId);
    if (!moveEvents) return null;

    const idx = binarySearchTime(moveEvents, this.currentTimeMs);
    const before = moveEvents[idx];
    if (!before) return null;
    const after = moveEvents[idx + 1];

    const getYaw = (e: EntitySpawnEvent | EntityMoveEvent) => e.type === EventType.ENTITY_SPAWN ? 0 : e.yaw;
    const getPitch = (e: EntitySpawnEvent | EntityMoveEvent) => e.type === EventType.ENTITY_SPAWN ? 0 : e.pitch;

    if (!after || after.timestampDeltaMs <= this.currentTimeMs) {
      return { x: before.x, y: before.y, z: before.z, yaw: getYaw(before), pitch: getPitch(before) };
    }

    const pos = interpolatePosition(before, after, this.currentTimeMs);
    const range = after.timestampDeltaMs - before.timestampDeltaMs;
    const t = range > 0 ? (this.currentTimeMs - before.timestampDeltaMs) / range : 0;

    return {
      ...pos,
      yaw: lerpAngle(getYaw(before), getYaw(after), t),
      pitch: lerpAngle(getPitch(before), getPitch(after), t),
    };
  }

  /** Get interpolated player position at current time */
  getInterpolatedPlayerPosition(uuid: string): { x: number; y: number; z: number; yaw: number; pitch: number } | null {
    const moveEvents = this.playerMoveEvents.get(uuid);
    if (!moveEvents) return null;

    const idx = binarySearchTime(moveEvents, this.currentTimeMs);
    const before = moveEvents[idx];
    if (!before) return null;
    const after = moveEvents[idx + 1];

    if (!after || after.timestampDeltaMs <= this.currentTimeMs) {
      return {
        x: before.x, y: before.y, z: before.z,
        yaw: before.yaw,
        pitch: before.pitch,
      };
    }

    const pos = interpolatePosition(before, after, this.currentTimeMs);

    const range = after.timestampDeltaMs - before.timestampDeltaMs;
    const t = range > 0 ? (this.currentTimeMs - before.timestampDeltaMs) / range : 0;

    return {
      ...pos,
      yaw: lerpAngle(before.yaw, after.yaw, t),
      pitch: lerpAngle(before.pitch, after.pitch, t),
    };
  }

  private readonly tick = (timestamp: number): void => {
    if (!this.playing) return;

    if (this.lastFrameTime !== null) {
      const realDelta = timestamp - this.lastFrameTime;
      const replayDelta = realDelta * this.rate;
      this.currentTimeMs = Math.min(this.currentTimeMs + replayDelta, this.totalDurationMs);

      // Process events and world state
      this.processEventsTo(this.currentTimeMs);
      const changes = this.worldState.advanceTo(this.currentTimeMs);
      this.notifyListeners(changes);

      // Auto-pause at end
      if (this.currentTimeMs >= this.totalDurationMs) {
        this.pause();
        return;
      }
    }

    this.lastFrameTime = timestamp;
    this.animationFrameId = requestAnimationFrame(this.tick);
  };

  private processEventsTo(timeMs: number): void {
    let event = this.replay.events[this.currentEventIndex];
    while (event !== undefined && event.timestampDeltaMs <= timeMs) {
      this.applyEvent(event);
      this.currentEventIndex++;
      event = this.replay.events[this.currentEventIndex];
    }
  }

  private applyEvent(event: ReplayEvent): void {
    switch (event.type) {
      case EventType.ENTITY_SPAWN: {
        this.entities.set(event.entityId, {
          entityId: event.entityId,
          entityTypeId: event.entityTypeId,
          entityTypeName: textDecoder.decode(event.metadata),
          x: event.x, y: event.y, z: event.z,
          yaw: 0, pitch: 0,
          alive: true,
        });
        break;
      }
      case EventType.ENTITY_MOVE: {
        const entity = this.entities.get(event.entityId);
        if (entity) {
          entity.x = event.x; entity.y = event.y; entity.z = event.z;
          entity.yaw = event.yaw;
          entity.pitch = event.pitch;
        }
        break;
      }
      case EventType.ENTITY_REMOVE:
        this.entities.delete(event.entityId);
        break;
      case EventType.PLAYER_SPAWN: {
        const skinUuid = decodeSkinUuid(event.equipment, event.uuid);
        this.players.set(event.uuid, {
          uuid: event.uuid,
          playerName: event.playerName,
          x: event.x, y: event.y, z: event.z,
          yaw: event.yaw,
          pitch: event.pitch,
          alive: true,
          sneaking: false,
          lastSwingTime: 0,
          lastDamageTime: 0,
          skinUuid,
          heldItem: '',
          equipment: new Map(),
          health: 20,
          food: 20,
          saturation: 5,
          effects: [],
          inventory: new Map(),
        });
        break;
      }
      case EventType.PLAYER_MOVE: {
        let player = this.players.get(event.uuid);
        if (!player) {
          // Auto-create player on first move if spawn was missing (e.g. evicted from rolling buffer)
          player = {
            uuid: event.uuid,
            playerName: i18n.t('common.unknown'),
            x: event.x, y: event.y, z: event.z,
            yaw: event.yaw,
            pitch: event.pitch,
            alive: true,
            sneaking: false,
            lastSwingTime: 0,
            lastDamageTime: 0,
            skinUuid: event.uuid,
            heldItem: '',
            equipment: new Map(),
            health: 20,
            food: 20,
            saturation: 5,
            effects: [],
            inventory: new Map(),
          };
          this.players.set(event.uuid, player);
        } else {
          player.x = event.x; player.y = event.y; player.z = event.z;
          player.yaw = event.yaw;
          player.pitch = event.pitch;
        }
        break;
      }
      case EventType.PLAYER_REMOVE:
        this.players.delete(event.uuid);
        break;
      case EventType.PLAYER_ANIM: {
        const player = this.players.get(event.uuid);
        if (player) {
          ReplayEngine.applyAnimation(player, event);
        }
        break;
      }
      case EventType.PLAYER_EQUIPMENT: {
        const player = this.players.get(event.uuid);
        if (player) {
          player.heldItem = event.itemName;
          player.equipment.set(0, { itemName: event.itemName }); // backward compat: slot 0 = main hand
        }
        break;
      }
      case EventType.PLAYER_EQUIPMENT_FULL: {
        const player = this.players.get(event.uuid);
        if (player) {
          player.equipment.clear();
          for (const slot of event.slots) {
            player.equipment.set(slot.slotId, { itemName: slot.itemName, ...optionalField('enchantments', slot.enchantments) });
          }
          // Keep heldItem in sync with main hand (slot 0)
          const mainHand = player.equipment.get(0);
          player.heldItem = mainHand?.itemName ?? '';
        }
        break;
      }
      case EventType.PLAYER_HEALTH: {
        const player = this.players.get(event.uuid);
        if (player) {
          player.health = event.health;
          player.food = event.food;
          player.saturation = event.saturation;
        }
        break;
      }
      case EventType.PLAYER_EFFECTS: {
        const player = this.players.get(event.uuid);
        if (player) {
          player.effects = event.effects.map(e => ({
            ...e,
            appliedAtMs: event.timestampDeltaMs,
          }));
        }
        break;
      }
      case EventType.PLAYER_INVENTORY: {
        const player = this.players.get(event.uuid);
        if (player) {
          if (event.isFullSnapshot) {
            player.inventory.clear();
          }
          for (const slot of event.slots) {
            if (slot.count === 0 || !slot.itemName) {
              player.inventory.delete(slot.slotIndex);
            } else {
              player.inventory.set(slot.slotIndex, { itemName: slot.itemName, count: slot.count, ...optionalField('enchantments', slot.enchantments) });
            }
          }
        }
        break;
      }
      case EventType.PLAYER_SKIN: {
        if (event.skinPng.length > 0) {
          this.playerSkins.set(event.uuid, event.skinPng);
        }
        break;
      }
      case EventType.BLOCK_CHANGE:
      case EventType.PLAYER_BLOCK_PLACE:
      case EventType.PLAYER_BLOCK_BREAK:
      case EventType.CHAT:
        break;
    }
  }

  private static applyAnimation(player: TrackedPlayer, event: PlayerAnimEvent): void {
    switch (event.animationType) {
      case AnimationType.SWING_MAIN_ARM:
        player.lastSwingTime = event.timestampDeltaMs;
        break;
      case AnimationType.SNEAK_START:
        player.sneaking = true;
        break;
      case AnimationType.SNEAK_STOP:
        player.sneaking = false;
        break;
      case AnimationType.DAMAGE:
        player.lastDamageTime = event.timestampDeltaMs;
        break;
      case AnimationType.SWING_OFF_ARM:
        break;
    }
  }

  private rebuildEntityState(): void {
    this.entities.clear();
    this.players.clear();
    this.currentEventIndex = 0;
    this.processEventsTo(this.currentTimeMs);
  }

  private notifyListeners(changes: BlockChange[]): void {
    const state = this.getState();
    for (const listener of this.listeners) {
      listener(state, changes);
    }
  }

  dispose(): void {
    this.pause();
    this.listeners.clear();
  }
}
