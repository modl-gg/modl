import type { Point } from "../studio/pointer";

export interface Rect {
  readonly x: number;
  readonly y: number;
  readonly width: number;
  readonly height: number;
}

const SLOTS_PER_ROW = 9;
const CHEST_WIDTH = 176;
const CHEST_BASE_HEIGHT = 114;
const SLOT_PITCH = 18;
const SLOT_FIRST_CENTRE = { x: 16, y: 26 } as const;
const CHEST_FILL = { x: 3, y: 14, width: 3, height: 3 } as const;
const CHAT_BOTTOM_MARGIN = 40;
const CHAT_LINE_HEIGHT = 9;

export class GuiLayout {
  readonly content: Rect;
  readonly scale: number;

  constructor(content: Rect, scale: number) {
    this.content = content;
    this.scale = scale;
  }

  get width(): number {
    return Math.ceil(this.content.width / this.scale);
  }

  get height(): number {
    return Math.ceil(this.content.height / this.scale);
  }

  point(x: number, y: number): Point {
    return { x: Math.round(this.content.x + x * this.scale), y: Math.round(this.content.y + y * this.scale) };
  }

  contains(point: Point): boolean {
    const rect = this.content;
    return point.x >= rect.x && point.x < rect.x + rect.width && point.y >= rect.y && point.y < rect.y + rect.height;
  }

  chest(rows: number): Rect {
    const height = CHEST_BASE_HEIGHT + rows * SLOT_PITCH;
    return { x: Math.floor((this.width - CHEST_WIDTH) / 2), y: Math.floor((this.height - height) / 2), width: CHEST_WIDTH, height };
  }

  chestFill(rows: number): Rect {
    const chest = this.chest(rows);
    return { x: chest.x + CHEST_FILL.x, y: chest.y + CHEST_FILL.y, width: CHEST_FILL.width, height: CHEST_FILL.height };
  }

  slot(rows: number, slot: number): Point {
    const row = Math.floor(slot / SLOTS_PER_ROW);
    if (slot < 0 || row >= rows) throw new Error(`slot ${String(slot)} is outside a ${String(rows)}-row menu`);
    const chest = this.chest(rows);
    return this.point(chest.x + SLOT_FIRST_CENTRE.x + SLOT_PITCH * (slot % SLOTS_PER_ROW), chest.y + SLOT_FIRST_CENTRE.y + SLOT_PITCH * row);
  }

  newestChatLine(textOffset: number): Point {
    return this.point(textOffset, this.height - CHAT_BOTTOM_MARGIN - CHAT_LINE_HEIGHT / 2);
  }
}
