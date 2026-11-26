import type { NineSliceBorder } from './AssetLibrary.ts';
import { isRecord, numberField, recordField, stringField } from './json.ts';

export interface SpriteRegion {
  readonly texture: string;
  readonly x: number;
  readonly y: number;
  readonly width: number;
  readonly height: number;
  readonly nineSlice: NineSliceBorder | null;
}

function region(texture: string, x: number, y: number, width: number, height: number, border: number | null = null): SpriteRegion {
  return { texture, x, y, width, height, nineSlice: border === null ? null : { left: border, top: border, right: border, bottom: border } };
}

const WIDGETS = 'gui/widgets';
const ICONS = 'gui/icons';
const INVENTORY = 'gui/container/inventory';

function heart(x: number, hardcore: boolean): SpriteRegion {
  return region(ICONS, x, hardcore ? 45 : 0, 9, 9);
}

const HEART_COLUMNS: Readonly<Record<string, number>> = {
  container: 16,
  container_blinking: 25,
  full: 52,
  half: 61,
  full_blinking: 70,
  half_blinking: 79,
  poisoned_full: 88,
  poisoned_half: 97,
  poisoned_full_blinking: 106,
  poisoned_half_blinking: 115,
  withered_full: 124,
  withered_half: 133,
  withered_full_blinking: 142,
  withered_half_blinking: 151,
  absorbing_full: 160,
  absorbing_half: 169,
};

const LEGACY_SPRITES: Readonly<Record<string, SpriteRegion>> = {
  'hud/hotbar': region(WIDGETS, 0, 0, 182, 22),
  'hud/hotbar_selection': region(WIDGETS, 0, 22, 24, 24),
  'hud/hotbar_offhand_left': region(WIDGETS, 24, 22, 29, 24),
  'hud/hotbar_offhand_right': region(WIDGETS, 53, 22, 29, 24),
  'widget/button_disabled': region(WIDGETS, 0, 46, 200, 20, 3),
  'widget/button': region(WIDGETS, 0, 66, 200, 20, 3),
  'widget/button_highlighted': region(WIDGETS, 0, 86, 200, 20, 3),
  'widget/slider': region(WIDGETS, 0, 46, 200, 20, 2),
  'widget/slider_handle': region(WIDGETS, 0, 66, 8, 20, 2),
  'widget/slider_handle_highlighted': region(WIDGETS, 0, 86, 8, 20, 2),
  'hud/food_empty': region(ICONS, 16, 27, 9, 9),
  'hud/food_half': region(ICONS, 61, 27, 9, 9),
  'hud/food_full': region(ICONS, 52, 27, 9, 9),
  'hud/food_empty_hunger': region(ICONS, 133, 27, 9, 9),
  'hud/food_half_hunger': region(ICONS, 97, 27, 9, 9),
  'hud/food_full_hunger': region(ICONS, 88, 27, 9, 9),
  'hud/armor_empty': region(ICONS, 16, 9, 9, 9),
  'hud/armor_half': region(ICONS, 25, 9, 9, 9),
  'hud/armor_full': region(ICONS, 34, 9, 9, 9),
  'hud/experience_bar_background': region(ICONS, 0, 64, 182, 5),
  'hud/experience_bar_progress': region(ICONS, 0, 69, 182, 5),
  'hud/crosshair': region(ICONS, 0, 0, 15, 15),
  'container/slot': region(INVENTORY, 7, 83, 18, 18),
};

const LEGACY_EFFECT_ICONS: Readonly<Record<string, number>> = {
  speed: 0,
  slowness: 1,
  haste: 2,
  mining_fatigue: 3,
  strength: 4,
  weakness: 5,
  poison: 6,
  regeneration: 7,
  invisibility: 8,
  hunger: 9,
  jump_boost: 10,
  nausea: 11,
  night_vision: 12,
  blindness: 13,
  resistance: 14,
  fire_resistance: 15,
  water_breathing: 16,
  wither: 17,
  health_boost: 23,
  absorption: 18,
};

export function legacySprite(id: string): SpriteRegion | null {
  const direct = LEGACY_SPRITES[id];
  if (direct !== undefined) return direct;
  if (id.startsWith('hud/heart/')) {
    const name = id.slice('hud/heart/'.length);
    const hardcore = name.includes('hardcore');
    const key = name === 'container_hardcore' ? 'container' : name.replace('hardcore_', '').replace('_hardcore', '');
    const column = HEART_COLUMNS[key];
    return column === undefined ? null : heart(column, hardcore);
  }
  if (id.startsWith('mob_effect/')) {
    const index = LEGACY_EFFECT_ICONS[id.slice('mob_effect/'.length)];
    return index === undefined ? null : region(INVENTORY, (index % 8) * 18, 198 + Math.floor(index / 8) * 18, 18, 18);
  }
  return null;
}

export function modernSpritePath(id: string): string {
  if (id.startsWith('mob_effect/')) return `textures/${id}.png`;
  return `textures/gui/sprites/${id}.png`;
}

export function nineSliceOf(metadata: unknown): NineSliceBorder | null {
  if (!isRecord(metadata)) return null;
  const gui = recordField(metadata, 'gui');
  const scaling = gui === null ? null : recordField(gui, 'scaling');
  if (scaling === null || stringField(scaling, 'type') !== 'nine_slice') return null;
  const border = scaling['border'];
  if (typeof border === 'number') return { left: border, top: border, right: border, bottom: border };
  if (isRecord(border)) {
    return {
      left: numberField(border, 'left') ?? 0,
      top: numberField(border, 'top') ?? 0,
      right: numberField(border, 'right') ?? 0,
      bottom: numberField(border, 'bottom') ?? 0,
    };
  }
  return null;
}
