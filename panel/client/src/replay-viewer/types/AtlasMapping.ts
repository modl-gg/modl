export interface AtlasUV {
  u: number;
  v: number;
  su: number;
  sv: number;
}

export interface BlockFaces {
  top: string;
  side: string;
  bottom: string;
  shape?: 'cross' | 'floor' | 'multiface' | undefined;
  faceMask?: number | undefined;
}

export interface AtlasItem {
  texture: string | null;
  isBlock: boolean;
}

export type ArmorTextureLayers = Partial<Record<'layer1' | 'layer2', string>>;

export interface AtlasMapping {
  textures: Record<string, AtlasUV>;
  blocks: Record<string, BlockFaces>;
  transparentStates?: number[] | undefined;
  stateToBlock?: Record<string, number> | undefined;
  waterLevels?: Record<string, number> | undefined;
  blockNameToState?: Record<string, number> | undefined;
  items?: Record<string, AtlasItem> | undefined;
  armorTextures?: Record<string, ArmorTextureLayers> | undefined;
  entityTextures?: Record<string, string> | undefined;
}

export const ITEM_TEXTURE_FALLBACKS: Readonly<Record<string, string>> = {
  enchanted_golden_apple: 'item/golden_apple',
  crossbow: 'item/crossbow_standby',
  compass: 'item/compass_16',
  recovery_compass: 'item/recovery_compass_16',
  clock: 'item/clock_00',
  tipped_arrow: 'item/tipped_arrow_base',
  debug_stick: 'item/stick',
};
