import { drawPixelArt, fillRect, pixelArt } from './pixels';

const HEART = pixelArt(['.##.##.', '#######', '#######', '.#####.', '..###..', '...#...']);
const HALF_HEART = pixelArt(['.##', '###', '###', '.##', '..#', '...']);
const FOOD = pixelArt(['...##', '..###', '.###.', '###..', '#.#..', '#....']);
const HALF_FOOD = pixelArt(['.....', '.....', '.....', '###..', '#.#..', '#....']);
const ARMOR = pixelArt(['##.##', '#####', '#####', '.###.', '..#..']);
const HALF_ARMOR = pixelArt(['##.', '###', '###', '.##', '..#']);

const HEART_COLORS: ReadonlyMap<string, number> = new Map([
  ['absorbing', 0xd4af37],
  ['poisoned', 0x7c9a2f],
  ['withered', 0x2b2b2b],
  ['frozen', 0x8fd3ff],
]);

function bevel(context: CanvasRenderingContext2D, x: number, y: number, width: number, height: number, face: string, light: string, dark: string): void {
  fillRect(context, x, y, width, height, '#000000');
  fillRect(context, x + 1, y + 1, width - 2, height - 2, face);
  fillRect(context, x + 1, y + 1, width - 2, 1, light);
  fillRect(context, x + 1, y + 1, 1, height - 2, light);
  fillRect(context, x + 1, y + height - 2, width - 2, 1, dark);
  fillRect(context, x + width - 2, y + 1, 1, height - 2, dark);
}

function inset(context: CanvasRenderingContext2D, x: number, y: number, width: number, height: number): void {
  fillRect(context, x, y, width, height, '#8b8b8b');
  fillRect(context, x, y, width - 1, 1, '#373737');
  fillRect(context, x, y, 1, height - 1, '#373737');
  fillRect(context, x + 1, y + height - 1, width - 1, 1, '#ffffff');
  fillRect(context, x + width - 1, y + 1, 1, height - 1, '#ffffff');
}

function heartColor(id: string): number {
  for (const [kind, color] of HEART_COLORS) {
    if (id.includes(kind)) return color;
  }
  return 0xd41c1c;
}

function drawHeart(context: CanvasRenderingContext2D, id: string, x: number, y: number): void {
  if (id.includes('container')) {
    drawPixelArt(context, HEART, x + 1, y + 1, 0x1a0000, 0.85);
    return;
  }
  drawPixelArt(context, id.endsWith('_half') ? HALF_HEART : HEART, x + 1, y + 1, heartColor(id));
}

function drawFood(context: CanvasRenderingContext2D, id: string, x: number, y: number): void {
  const hunger = id.includes('hunger');
  if (id.includes('empty')) {
    drawPixelArt(context, FOOD, x + 2, y + 2, 0x1f1206, 0.85);
    return;
  }
  drawPixelArt(context, id.includes('half') ? HALF_FOOD : FOOD, x + 2, y + 2, hunger ? 0x6b8e23 : 0xa0612b);
}

function drawArmor(context: CanvasRenderingContext2D, id: string, x: number, y: number): void {
  if (id.endsWith('empty')) {
    drawPixelArt(context, ARMOR, x + 2, y + 2, 0x222222, 0.85);
    return;
  }
  drawPixelArt(context, id.endsWith('half') ? HALF_ARMOR : ARMOR, x + 2, y + 2, 0xc6c6c6);
}

function drawHotbar(context: CanvasRenderingContext2D, x: number, y: number): void {
  fillRect(context, x, y, 182, 22, 'rgba(0, 0, 0, 0.55)');
  for (let slot = 0; slot < 9; slot += 1) {
    fillRect(context, x + 1 + slot * 20, y + 1, 20, 20, 'rgba(255, 255, 255, 0.08)');
    fillRect(context, x + 1 + slot * 20, y + 1, 20, 1, 'rgba(255, 255, 255, 0.18)');
  }
}

function drawSelection(context: CanvasRenderingContext2D, x: number, y: number): void {
  fillRect(context, x, y, 24, 2, '#ffffff');
  fillRect(context, x, y + 21, 24, 2, '#ffffff');
  fillRect(context, x, y, 2, 23, '#ffffff');
  fillRect(context, x + 22, y, 2, 23, '#ffffff');
}

export function drawFallbackSprite(context: CanvasRenderingContext2D, id: string, x: number, y: number, width: number, height: number): void {
  if (id.startsWith('widget/button')) {
    const disabled = id.endsWith('_disabled');
    const highlighted = id.endsWith('_highlighted');
    bevel(context, x, y, width, height, disabled ? '#2c2c2c' : highlighted ? '#7f89c6' : '#6f6f6f', disabled ? '#3a3a3a' : '#a8a8a8', '#454545');
    if (highlighted) {
      fillRect(context, x, y, width, 1, '#ffffff');
      fillRect(context, x, y + height - 1, width, 1, '#ffffff');
      fillRect(context, x, y, 1, height, '#ffffff');
      fillRect(context, x + width - 1, y, 1, height, '#ffffff');
    }
    return;
  }
  if (id.startsWith('widget/slider_handle')) {
    bevel(context, x, y, width, height, id.endsWith('_highlighted') ? '#8f8f8f' : '#6f6f6f', '#c0c0c0', '#454545');
    return;
  }
  if (id.startsWith('widget/slider')) {
    fillRect(context, x, y, width, height, '#000000');
    fillRect(context, x + 1, y + 1, width - 2, height - 2, '#262626');
    return;
  }
  if (id === 'hud/hotbar') {
    drawHotbar(context, x, y);
    return;
  }
  if (id === 'hud/hotbar_selection') {
    drawSelection(context, x, y);
    return;
  }
  if (id.startsWith('hud/hotbar_offhand')) {
    fillRect(context, x, y + 1, 22, 22, 'rgba(0, 0, 0, 0.55)');
    return;
  }
  if (id.startsWith('hud/heart/')) {
    drawHeart(context, id, x, y);
    return;
  }
  if (id.startsWith('hud/food_')) {
    drawFood(context, id, x, y);
    return;
  }
  if (id.startsWith('hud/armor_')) {
    drawArmor(context, id, x, y);
    return;
  }
  if (id === 'hud/experience_bar_background') {
    fillRect(context, x, y, width, height, '#000000');
    fillRect(context, x + 1, y + 1, width - 2, height - 2, '#1d3b0a');
    return;
  }
  if (id === 'hud/experience_bar_progress') {
    fillRect(context, x, y + 1, width, height - 2, '#7efc20');
    return;
  }
  if (id.startsWith('hud/effect_background')) {
    bevel(context, x, y, width, height, '#3b3b4f', '#5b5b7a', '#25252f');
    return;
  }
  if (id === 'container/slot') {
    inset(context, x, y, width, height);
  }
}

export function fallbackSpriteSize(id: string): { readonly width: number; readonly height: number } {
  if (id === 'hud/hotbar') return { width: 182, height: 22 };
  if (id === 'hud/hotbar_selection') return { width: 24, height: 23 };
  if (id.startsWith('hud/hotbar_offhand')) return { width: 29, height: 24 };
  if (id.startsWith('hud/experience_bar')) return { width: 182, height: 5 };
  if (id.startsWith('hud/effect_background')) return { width: 24, height: 24 };
  if (id === 'container/slot') return { width: 18, height: 18 };
  if (id.startsWith('widget/slider_handle')) return { width: 8, height: 20 };
  if (id.startsWith('widget/')) return { width: 200, height: 20 };
  return { width: 9, height: 9 };
}

export function drawFallbackInventory(context: CanvasRenderingContext2D, x: number, y: number): void {
  bevel(context, x, y, 176, 166, '#c6c6c6', '#ffffff', '#555555');
  fillRect(context, x + 26, y + 8, 49, 70, '#000000');
  const slots: readonly (readonly [number, number])[] = [
    ...[0, 1, 2, 3].map((row) => [7, 7 + row * 18] as const),
    [76, 61],
    ...Array.from({ length: 27 }, (_, index) => [7 + (index % 9) * 18, 83 + Math.floor(index / 9) * 18] as const),
    ...Array.from({ length: 9 }, (_, index) => [7 + index * 18, 141] as const),
  ];
  for (const [slotX, slotY] of slots) inset(context, x + slotX, y + slotY, 18, 18);
}
