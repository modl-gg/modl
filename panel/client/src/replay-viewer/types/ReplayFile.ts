// Binary format types - mirrors Java modl-replay-format exactly
// See FORMAT.md for the canonical binary spec

export interface ReplayHeader {
  version: number;       // uint16
  startTime: number;     // int64 as JS number (safe for timestamps)
  mcVersion: string;
  targetX: number;       // int32
  targetY: number;       // int32
  targetZ: number;       // int32
  radiusBlocks: number;  // int32
}

export interface BlockSnapshot {
  x: number;       // int32
  y: number;       // int16
  z: number;       // int32
  stateId: number; // int32
}

export const EventType = {
  BLOCK_CHANGE: 0x01,
  ENTITY_SPAWN: 0x02,
  ENTITY_MOVE: 0x03,
  ENTITY_REMOVE: 0x04,
  PLAYER_SPAWN: 0x05,
  PLAYER_MOVE: 0x06,
  PLAYER_REMOVE: 0x07,
  PLAYER_ANIM: 0x08,
  CHAT: 0x09,
  PLAYER_EQUIPMENT: 0x0A,
  PLAYER_EQUIPMENT_FULL: 0x0B,
  PLAYER_HEALTH: 0x0C,
  PLAYER_EFFECTS: 0x0D,
  PLAYER_INVENTORY: 0x0E,
  PLAYER_SKIN: 0x0F,
  PLAYER_BLOCK_PLACE: 0x10,
  PLAYER_BLOCK_BREAK: 0x11,
} as const;

export type EventTypeId = (typeof EventType)[keyof typeof EventType];

export const AnimationType = {
  SWING_MAIN_ARM: 0,
  SWING_OFF_ARM: 1,
  SNEAK_START: 2,
  SNEAK_STOP: 3,
  DAMAGE: 4,
} as const;

export type AnimationTypeId = (typeof AnimationType)[keyof typeof AnimationType];

const ANIMATION_TYPE_IDS: ReadonlySet<number> = new Set<number>(Object.values(AnimationType));

export function isAnimationTypeId(value: number): value is AnimationTypeId {
  return ANIMATION_TYPE_IDS.has(value);
}

// --- Event payload types ---

interface BaseEvent {
  timestampDeltaMs: number; // int32
}

export interface BlockChangeEvent extends BaseEvent {
  type: typeof EventType.BLOCK_CHANGE;
  x: number;       // int32
  y: number;       // int16
  z: number;       // int32
  stateId: number; // int32
}

export interface EntitySpawnEvent extends BaseEvent {
  type: typeof EventType.ENTITY_SPAWN;
  entityId: number;     // int32
  entityTypeId: number; // int16
  x: number;            // float32
  y: number;            // float32
  z: number;            // float32
  metadata: Uint8Array<ArrayBuffer>;
}

export interface EntityMoveEvent extends BaseEvent {
  type: typeof EventType.ENTITY_MOVE;
  entityId: number; // int32
  x: number;        // float32
  y: number;        // float32
  z: number;        // float32
  yaw: number;      // degrees (float32 in v4+, decoded from int16 in v1-v3)
  pitch: number;    // degrees (float32 in v4+, decoded from int16 in v1-v3)
}

export interface EntityRemoveEvent extends BaseEvent {
  type: typeof EventType.ENTITY_REMOVE;
  entityId: number; // int32
}

export interface PlayerSpawnEvent extends BaseEvent {
  type: typeof EventType.PLAYER_SPAWN;
  uuid: string;       // UUID as "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
  playerName: string;
  x: number;          // float32
  y: number;          // float32
  z: number;          // float32
  yaw: number;        // degrees (float32 in v4+, decoded from int16 in v1-v3)
  pitch: number;      // degrees (float32 in v4+, decoded from int16 in v1-v3)
  equipment: Uint8Array<ArrayBuffer>;
}

export interface PlayerMoveEvent extends BaseEvent {
  type: typeof EventType.PLAYER_MOVE;
  uuid: string;
  x: number;     // float32
  y: number;     // float32
  z: number;     // float32
  yaw: number;   // degrees (float32 in v4+, decoded from int16 in v1-v3)
  pitch: number; // degrees (float32 in v4+, decoded from int16 in v1-v3)
}

export interface PlayerRemoveEvent extends BaseEvent {
  type: typeof EventType.PLAYER_REMOVE;
  uuid: string;
}

export interface PlayerAnimEvent extends BaseEvent {
  type: typeof EventType.PLAYER_ANIM;
  uuid: string;
  animationType: AnimationTypeId;
}

export interface ChatEvent extends BaseEvent {
  type: typeof EventType.CHAT;
  uuid: string;
  message: string;
}

export interface PlayerEquipmentEvent extends BaseEvent {
  type: typeof EventType.PLAYER_EQUIPMENT;
  uuid: string;
  itemName: string;
}

export interface PlayerEquipmentFullEvent extends BaseEvent {
  type: typeof EventType.PLAYER_EQUIPMENT_FULL;
  uuid: string;
  slots: { slotId: number; itemName: string; enchantments?: EnchantEntry[] }[];
}

export interface PlayerHealthEvent extends BaseEvent {
  type: typeof EventType.PLAYER_HEALTH;
  uuid: string;
  health: number;    // float32
  food: number;      // uint8
  saturation: number; // float32
}

export interface PlayerEffectEntry {
  effectTypeId: number; // uint8
  amplifier: number;    // uint8
  durationTicks: number; // int32
}

export interface PlayerEffectsEvent extends BaseEvent {
  type: typeof EventType.PLAYER_EFFECTS;
  uuid: string;
  effects: PlayerEffectEntry[];
}

export interface EnchantEntry {
  id: string;
  level: number;
}

export interface PlayerInventorySlot {
  slotIndex: number; // uint8
  itemName: string;
  count: number;     // uint8
  enchantments?: EnchantEntry[];
}

export interface PlayerInventoryEvent extends BaseEvent {
  type: typeof EventType.PLAYER_INVENTORY;
  uuid: string;
  isFullSnapshot: boolean;
  slots: PlayerInventorySlot[];
}

export interface PlayerSkinEvent extends BaseEvent {
  type: typeof EventType.PLAYER_SKIN;
  uuid: string;
  skinPng: Uint8Array<ArrayBuffer>;
}

export interface PlayerBlockPlaceEvent extends BaseEvent {
  type: typeof EventType.PLAYER_BLOCK_PLACE;
  placerUuid: string;
  x: number;       // int32
  y: number;       // int32
  z: number;       // int32
  stateId: number; // int32
}

export interface PlayerBlockBreakEvent extends BaseEvent {
  type: typeof EventType.PLAYER_BLOCK_BREAK;
  breakerUuid: string;
  x: number;       // int32
  y: number;       // int32
  z: number;       // int32
  previousStateId: number; // int32
}

// Discriminated union of all event types
export type ReplayEvent =
  | BlockChangeEvent
  | EntitySpawnEvent
  | EntityMoveEvent
  | EntityRemoveEvent
  | PlayerSpawnEvent
  | PlayerMoveEvent
  | PlayerRemoveEvent
  | PlayerAnimEvent
  | ChatEvent
  | PlayerEquipmentEvent
  | PlayerEquipmentFullEvent
  | PlayerHealthEvent
  | PlayerEffectsEvent
  | PlayerInventoryEvent
  | PlayerSkinEvent
  | PlayerBlockPlaceEvent
  | PlayerBlockBreakEvent;

// Complete parsed replay file
export interface ParsedReplay {
  header: ReplayHeader;
  snapshot: BlockSnapshot[];
  events: ReplayEvent[];
}
