export type CameraMode = 'follow' | 'free' | 'first-person';

export type LightingMode = 'game' | 'full-bright';

export type GameMode = 'survival' | 'creative' | 'adventure' | 'spectator';

export interface PlaybackState {
  readonly ready: boolean;
  readonly error: string | null;
  readonly playing: boolean;
  readonly timeMs: number;
  readonly durationMs: number;
  readonly speed: number;
  readonly followedEntityId: number | null;
  readonly cameraMode: CameraMode;
  readonly lighting: LightingMode;
}

export interface ItemStackView {
  readonly slot: number;
  readonly item: string;
  readonly count: number;
  readonly enchanted: boolean;
}

export interface EffectView {
  readonly effect: string;
  readonly amplifier: number;
}

export interface ArmorSlotsView {
  readonly head: ItemStackView | null;
  readonly chest: ItemStackView | null;
  readonly legs: ItemStackView | null;
  readonly feet: ItemStackView | null;
}

export interface ReplayPlayerInfo {
  readonly entityId: number;
  readonly uuid: string;
  readonly name: string;
  readonly skinHash: string | null;
  readonly slim: boolean;
  readonly isTarget: boolean;
  readonly present: boolean;
  readonly health: number | null;
  readonly maxHealth: number | null;
  readonly absorption: number | null;
  readonly food: number | null;
  readonly saturation: number | null;
  readonly armor: number | null;
  readonly xpLevel: number | null;
  readonly xpProgress: number | null;
  readonly selectedSlot: number | null;
  readonly inventory: readonly ItemStackView[];
  readonly mainHand: ItemStackView | null;
  readonly offhand: ItemStackView | null;
  readonly armorSlots: ArmorSlotsView;
  readonly effects: readonly EffectView[];
  readonly gamemode: GameMode | null;
  readonly hardcore: boolean;
}

export const MARKER_KINDS = ['chat', 'damage', 'block_break', 'block_place', 'death', 'capture'] as const;

export type MarkerKind = (typeof MARKER_KINDS)[number];

export interface ReplayMarker {
  readonly timeMs: number;
  readonly kind: MarkerKind;
}

export interface ChatSegment {
  readonly text: string;
  readonly color: string;
  readonly bold: boolean;
  readonly italic: boolean;
  readonly underlined: boolean;
  readonly strikethrough: boolean;
  readonly obfuscated: boolean;
}

export interface ChatLine {
  readonly timeMs: number;
  readonly segments: readonly ChatSegment[];
}

export type PlaybackListener = (state: PlaybackState) => void;

export interface ScreenPoint {
  readonly x: number;
  readonly y: number;
}

export type AimListener = (point: ScreenPoint | null) => void;

export interface ReplayPlayer {
  readonly durationMs: number;
  play(): void;
  pause(): void;
  seek(timeMs: number): void;
  setSpeed(speed: number): void;
  follow(entityId: number | null): void;
  setCameraMode(mode: CameraMode): void;
  captureMouse(): void;
  setFreeCamera(x: number, y: number, z: number, yaw: number, pitch: number): void;
  setLighting(mode: LightingMode): void;
  renderPlayerPortrait(entityId: number, width: number, height: number): ImageBitmap | null;
  state(): PlaybackState;
  subscribe(listener: PlaybackListener): () => void;
  subscribeAim(listener: AimListener): () => void;
  players(): readonly ReplayPlayerInfo[];
  markers(): readonly ReplayMarker[];
  chatUntil(timeMs: number): readonly ChatLine[];
  dispose(): void;
}
