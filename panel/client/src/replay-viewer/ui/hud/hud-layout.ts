export interface SpritePlacement {
  readonly sprites: readonly string[];
  readonly x: number;
  readonly y: number;
}

export type HeartKind = 'normal' | 'poisoned' | 'withered' | 'frozen';

export interface HeartInput {
  readonly health: number;
  readonly maxHealth: number;
  readonly absorption: number;
  readonly hardcore: boolean;
  readonly kind: HeartKind;
}

export interface HeartLayout {
  readonly placements: readonly SpritePlacement[];
  readonly rows: number;
  readonly rowHeight: number;
}

const HEART_SPACING = 8;
const HEARTS_PER_ROW = 10;
const HEART_PREFIX = 'hud/heart/';

function heartSprites(kind: HeartKind | 'absorbing', hardcore: boolean, half: boolean): string[] {
  const fill = half ? 'half' : 'full';
  const prefix = kind === 'normal' ? '' : `${kind}_`;
  const plain = `${HEART_PREFIX}${prefix}${fill}`;
  const fallbacks = kind === 'normal' ? [] : [`${HEART_PREFIX}${fill}`];
  return hardcore ? [`${HEART_PREFIX}${prefix}hardcore_${fill}`, plain, ...fallbacks] : [plain, ...fallbacks];
}

function containerSprites(hardcore: boolean): string[] {
  return hardcore ? [`${HEART_PREFIX}container_hardcore`, `${HEART_PREFIX}container`] : [`${HEART_PREFIX}container`];
}

export function heartLayout(input: HeartInput, left: number, top: number): HeartLayout {
  const health = Math.max(0, Math.ceil(input.health));
  const absorption = Math.max(0, Math.ceil(input.absorption));
  const healthHearts = Math.ceil(Math.max(input.maxHealth, health) / 2);
  const absorptionHearts = Math.ceil(absorption / 2);
  const total = healthHearts + absorptionHearts;
  const rows = Math.max(1, Math.ceil(total / HEARTS_PER_ROW));
  const rowHeight = Math.max(10 - (rows - 2), 3);
  const placements: SpritePlacement[] = [];
  const absorbingKind = input.kind === 'withered' ? 'withered' : 'absorbing';

  for (let index = total - 1; index >= 0; index -= 1) {
    const x = left + (index % HEARTS_PER_ROW) * HEART_SPACING;
    const y = top - Math.floor(index / HEARTS_PER_ROW) * rowHeight;
    placements.push({ sprites: containerSprites(input.hardcore), x, y });
    if (index >= healthHearts) {
      const points = (index - healthHearts) * 2;
      if (points < absorption) placements.push({ sprites: heartSprites(absorbingKind, input.hardcore, points + 1 === absorption), x, y });
    } else if (index * 2 < health) {
      placements.push({ sprites: heartSprites(input.kind, input.hardcore, index * 2 + 1 === health), x, y });
    }
  }
  return { placements, rows, rowHeight };
}

export function foodPlacements(food: number, hungry: boolean, right: number, top: number): SpritePlacement[] {
  const suffix = hungry ? '_hunger' : '';
  const value = Math.max(0, Math.round(food));
  const placements: SpritePlacement[] = [];
  for (let index = 0; index < 10; index += 1) {
    const x = right - index * HEART_SPACING - 9;
    placements.push({ sprites: [`hud/food_empty${suffix}`, 'hud/food_empty'], x, y: top });
    if (index * 2 + 1 < value) placements.push({ sprites: [`hud/food_full${suffix}`, 'hud/food_full'], x, y: top });
    else if (index * 2 + 1 === value) placements.push({ sprites: [`hud/food_half${suffix}`, 'hud/food_half'], x, y: top });
  }
  return placements;
}

export function armorPlacements(armor: number, left: number, top: number): SpritePlacement[] {
  const value = Math.max(0, Math.round(armor));
  if (value <= 0) return [];
  return Array.from({ length: 10 }, (_, index) => {
    const sprite = index * 2 + 1 < value ? 'hud/armor_full' : index * 2 + 1 === value ? 'hud/armor_half' : 'hud/armor_empty';
    return { sprites: [sprite], x: left + index * HEART_SPACING, y: top };
  });
}

export const HOTBAR_WIDTH = 182;
export const HOTBAR_HEIGHT = 22;

export interface HudAnchors {
  readonly centerX: number;
  readonly bottom: number;
  readonly hotbarLeft: number;
  readonly hotbarTop: number;
  readonly experienceTop: number;
  readonly levelTop: number;
  readonly statusTop: number;
  readonly foodRight: number;
}

export function hudAnchors(width: number, bottom: number): HudAnchors {
  const centerX = Math.floor(width / 2);
  return {
    centerX,
    bottom,
    hotbarLeft: centerX - 91,
    hotbarTop: bottom - HOTBAR_HEIGHT,
    experienceTop: bottom - 29,
    levelTop: bottom - 35,
    statusTop: bottom - 39,
    foodRight: centerX + 91,
  };
}

export function hotbarSlotX(anchors: HudAnchors, slot: number): number {
  return anchors.hotbarLeft + 1 + slot * 20 + 2;
}

export function hotbarItemY(anchors: HudAnchors): number {
  return anchors.bottom - 16 - 3;
}

export function experienceProgressWidth(progress: number): number {
  return Math.floor(Math.min(1, Math.max(0, progress)) * (HOTBAR_WIDTH + 1));
}
