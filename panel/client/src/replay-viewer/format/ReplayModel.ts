import type { BlockState } from './BlockState.ts';
import type { SectionStates } from './WorldLayout.ts';

export const GAME_MODES = ['survival', 'creative', 'adventure', 'spectator'] as const;

export type GameModeName = (typeof GAME_MODES)[number];

export const POSES = ['standing', 'fall_flying', 'sleeping', 'swimming', 'spin_attack', 'crouching', 'long_jumping', 'dying', 'sitting'] as const;

export type PoseName = (typeof POSES)[number];

export const EQUIPMENT_SLOTS = ['mainhand', 'offhand', 'head', 'chest', 'legs', 'feet', 'body', 'saddle'] as const;

export type EquipmentSlotName = (typeof EQUIPMENT_SLOTS)[number];

export type AnimationName = 'swing_main' | 'swing_off' | 'critical' | 'magic_critical' | 'wake_up';

export const CHAT_KINDS = ['system', 'player', 'action_bar'] as const;

export type ChatKind = (typeof CHAT_KINDS)[number];

export interface ReplayHeader {
  readonly formatVersion: number;
  readonly minecraftVersion: string;
  readonly dimension: string;
  readonly minY: number;
  readonly height: number;
  readonly startEpochMs: number;
  readonly durationMs: number;
  readonly targetUuid: string | null;
  readonly targetName: string | null;
  readonly markerMs: number | null;
  readonly targetEntityId: number | null;
  readonly hardcore: boolean;
  readonly lightRecorded: boolean;
  readonly radiusBlocks: number;
  readonly truncated: boolean;
}

export interface WorldDimension {
  readonly key: string;
  readonly minY: number;
  readonly height: number;
  readonly hasSkyLight: boolean;
}

export interface DimensionInfo extends WorldDimension {
  readonly world: number;
}

export const DEFAULT_DIMENSION: WorldDimension = { key: 'minecraft:overworld', minY: 0, height: 256, hasSkyLight: true };

export interface ReplaySection {
  readonly x: number;
  readonly y: number;
  readonly z: number;
  readonly states: SectionStates | null;
  readonly skyLight: Uint8Array | null;
  readonly blockLight: Uint8Array | null;
  readonly biomes: Uint32Array | null;
}

export interface BlockEntityData {
  readonly x: number;
  readonly y: number;
  readonly z: number;
  readonly type: string;
  readonly frontText: readonly string[];
  readonly backText: readonly string[];
  readonly skinHash: string | null;
}

export interface ReplayWorld {
  readonly dimension: DimensionInfo;
  readonly sections: readonly ReplaySection[];
  readonly blockEntities: readonly BlockEntityData[];
}

export interface ItemStackData {
  readonly item: string;
  readonly count: number;
  readonly enchanted: boolean;
  readonly dyeColor: number | null;
  readonly customName: string | null;
  readonly damage: number;
  readonly maxDamage: number;
}

export interface EntityFlags {
  readonly onFire: boolean;
  readonly sneaking: boolean;
  readonly sprinting: boolean;
  readonly swimming: boolean;
  readonly invisible: boolean;
  readonly glowing: boolean;
  readonly gliding: boolean;
}

export interface EntityMetadataData {
  readonly flags?: EntityFlags;
  readonly pose?: PoseName;
  readonly item?: ItemStackData | null;
  readonly customName?: string;
  readonly customNameVisible?: boolean;
  readonly baby?: boolean;
  readonly health?: number;
  readonly blockState?: number;
  readonly skinParts?: number;
  readonly leftHanded?: boolean;
  readonly variant?: number;
}

export interface EquipmentData {
  readonly slot: EquipmentSlotName;
  readonly item: ItemStackData | null;
}

export interface EffectData {
  readonly effect: string;
  readonly amplifier: number;
  readonly durationTicks: number;
}

export interface EntitySnapshotData {
  readonly entityId: number;
  readonly type: string;
  readonly uuid: string | null;
  readonly x: number;
  readonly y: number;
  readonly z: number;
  readonly yaw: number;
  readonly pitch: number;
  readonly headYaw: number;
  readonly onGround: boolean;
  readonly metadata: EntityMetadataData;
  readonly equipment: readonly EquipmentData[];
  readonly effects: readonly EffectData[];
  readonly vehicle: number | null;
}

export interface PlayerInfoData {
  readonly uuid: string;
  readonly name: string;
  readonly skinHash: string | null;
  readonly slim: boolean;
  readonly gameMode: GameModeName | null;
  readonly skinPng: Uint8Array<ArrayBuffer> | null;
}

export interface InventorySlotData {
  readonly slot: number;
  readonly item: ItemStackData | null;
}

export interface TargetStateData {
  readonly health: number;
  readonly maxHealth: number;
  readonly absorption: number;
  readonly food: number;
  readonly saturation: number;
  readonly armor: number;
  readonly xpLevel: number;
  readonly xpProgress: number;
  readonly selectedSlot: number;
  readonly gameMode: GameModeName | null;
  readonly inventory: readonly InventorySlotData[];
}

export interface KeyframeData {
  readonly timeMs: number;
  readonly world: number;
  readonly players: readonly PlayerInfoData[];
  readonly entities: readonly EntitySnapshotData[];
  readonly target: TargetStateData | null;
  readonly timeOfDay: number;
  readonly rain: number;
  readonly thunder: number;
}

export type ReplayEventData =
  | { readonly kind: 'spawn'; readonly timeMs: number; readonly entity: EntitySnapshotData }
  | { readonly kind: 'remove'; readonly timeMs: number; readonly entityIds: readonly number[] }
  | { readonly kind: 'move'; readonly timeMs: number; readonly entityId: number; readonly dx: number; readonly dy: number; readonly dz: number; readonly yaw: number | null; readonly pitch: number | null; readonly headYaw: number | null; readonly onGround: boolean }
  | { readonly kind: 'teleport'; readonly timeMs: number; readonly entityId: number; readonly x: number; readonly y: number; readonly z: number; readonly yaw: number; readonly pitch: number; readonly onGround: boolean }
  | { readonly kind: 'metadata'; readonly timeMs: number; readonly entityId: number; readonly metadata: EntityMetadataData }
  | { readonly kind: 'equipment'; readonly timeMs: number; readonly entityId: number; readonly equipment: readonly EquipmentData[] }
  | { readonly kind: 'animation'; readonly timeMs: number; readonly entityId: number; readonly animation: AnimationName }
  | { readonly kind: 'hurt'; readonly timeMs: number; readonly entityId: number }
  | { readonly kind: 'death'; readonly timeMs: number; readonly entityId: number }
  | { readonly kind: 'effect'; readonly timeMs: number; readonly entityId: number; readonly effect: EffectData }
  | { readonly kind: 'effect_remove'; readonly timeMs: number; readonly entityId: number; readonly effect: string }
  | { readonly kind: 'passengers'; readonly timeMs: number; readonly vehicle: number; readonly passengers: readonly number[] }
  | { readonly kind: 'pickup'; readonly timeMs: number; readonly collected: number; readonly collector: number }
  | { readonly kind: 'block'; readonly timeMs: number; readonly x: number; readonly y: number; readonly z: number; readonly state: number }
  | { readonly kind: 'break_progress'; readonly timeMs: number; readonly entityId: number; readonly x: number; readonly y: number; readonly z: number; readonly stage: number }
  | { readonly kind: 'player_info'; readonly timeMs: number; readonly player: PlayerInfoData }
  | { readonly kind: 'player_remove'; readonly timeMs: number; readonly uuids: readonly string[] }
  | { readonly kind: 'chat'; readonly timeMs: number; readonly chatKind: ChatKind; readonly json: string; readonly senderUuid: string | null }
  | { readonly kind: 'target_health'; readonly timeMs: number; readonly health: number; readonly food: number; readonly saturation: number }
  | { readonly kind: 'target_attributes'; readonly timeMs: number; readonly maxHealth: number | null; readonly absorption: number | null; readonly armor: number | null }
  | { readonly kind: 'target_experience'; readonly timeMs: number; readonly level: number; readonly progress: number }
  | { readonly kind: 'target_slot'; readonly timeMs: number; readonly slot: number }
  | { readonly kind: 'target_inventory'; readonly timeMs: number; readonly replaceAll: boolean; readonly slots: readonly InventorySlotData[] }
  | { readonly kind: 'target_gamemode'; readonly timeMs: number; readonly gameMode: GameModeName | null }
  | { readonly kind: 'dimension'; readonly timeMs: number; readonly dimension: DimensionInfo }
  | { readonly kind: 'time'; readonly timeMs: number; readonly timeOfDay: number }
  | { readonly kind: 'weather'; readonly timeMs: number; readonly rain: number; readonly thunder: number };

export interface ParsedReplay {
  readonly header: ReplayHeader;
  readonly palette: readonly BlockState[];
  readonly biomeKeys: readonly string[];
  readonly worlds: ReadonlyMap<number, ReplayWorld>;
  readonly keyframes: readonly KeyframeData[];
  readonly events: readonly ReplayEventData[];
}

export const REPLAY_MAGIC: readonly number[] = [0x4d, 0x4f, 0x44, 0x4c];

export class ReplayFormatError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ReplayFormatError';
  }
}
