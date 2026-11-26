import type { AssetLibrary, GuiSprite, NineSliceBorder } from '../../assets/AssetLibrary';
import { argbCss, GUI_COLORS, rgbCss } from './colors';
import { drawFallbackInventory, drawFallbackSprite, fallbackSpriteSize } from './fallback-art';

export const ITEM_SIZE = 16;
const MISSING_HALF = ITEM_SIZE / 2;
const GLINT_RGB = 0x8040cc;
const GLINT_ALPHA = 0.45;
const TOOLTIP_BACKGROUND = 0xf0100010;
const TOOLTIP_BORDER_TOP = 0x505000ff;
const TOOLTIP_BORDER_BOTTOM = 0x5028007f;
const TOOLTIP_LINE_HEIGHT = 10;

export interface TextOptions {
  readonly color?: number;
  readonly shadow?: boolean;
  readonly bold?: boolean;
  readonly italic?: boolean;
}

type ImageSource = ImageBitmap | HTMLCanvasElement;

const glintCache = new WeakMap<ImageBitmap, HTMLCanvasElement>();

function glinted(icon: ImageBitmap): HTMLCanvasElement {
  const cached = glintCache.get(icon);
  if (cached !== undefined) return cached;
  const canvas = document.createElement('canvas');
  canvas.width = icon.width;
  canvas.height = icon.height;
  const context = canvas.getContext('2d');
  if (context !== null) {
    context.drawImage(icon, 0, 0);
    context.globalCompositeOperation = 'source-atop';
    context.fillStyle = rgbCss(GLINT_RGB, GLINT_ALPHA);
    context.fillRect(0, 0, canvas.width, canvas.height);
  }
  glintCache.set(icon, canvas);
  return canvas;
}

function clampBorder(border: NineSliceBorder, width: number, height: number): NineSliceBorder {
  const horizontal = border.left + border.right;
  const vertical = border.top + border.bottom;
  const left = horizontal > width ? Math.floor(width / 2) : border.left;
  const top = vertical > height ? Math.floor(height / 2) : border.top;
  return {
    left,
    right: horizontal > width ? width - left : border.right,
    top,
    bottom: vertical > height ? height - top : border.bottom,
  };
}

function halvesBorder(sprite: GuiSprite, width: number, height: number): NineSliceBorder {
  const left = Math.min(Math.floor(width / 2), sprite.width);
  const top = Math.min(Math.floor(height / 2), sprite.height);
  return { left, right: Math.min(width - left, sprite.width), top, bottom: Math.min(height - top, sprite.height) };
}

export class GuiPainter {
  readonly context: CanvasRenderingContext2D;
  readonly assets: AssetLibrary;

  constructor(context: CanvasRenderingContext2D, assets: AssetLibrary) {
    this.context = context;
    this.assets = assets;
    context.imageSmoothingEnabled = false;
  }

  fill(x: number, y: number, width: number, height: number, argb: number): void {
    this.context.fillStyle = argbCss(argb);
    this.context.fillRect(x, y, width, height);
  }

  gradient(x: number, y: number, width: number, height: number, topArgb: number, bottomArgb: number): void {
    const gradient = this.context.createLinearGradient(0, y, 0, y + height);
    gradient.addColorStop(0, argbCss(topArgb));
    gradient.addColorStop(1, argbCss(bottomArgb));
    this.context.fillStyle = gradient;
    this.context.fillRect(x, y, width, height);
  }

  firstSprite(ids: readonly string[]): GuiSprite | null {
    for (const id of ids) {
      const sprite = this.assets.guiSprite(id);
      if (sprite !== null) return sprite;
    }
    return null;
  }

  sprite(ids: string | readonly string[], x: number, y: number): void {
    const candidates = typeof ids === 'string' ? [ids] : ids;
    const sprite = this.firstSprite(candidates);
    if (sprite !== null) {
      this.context.drawImage(sprite.image, x, y);
      return;
    }
    const id = candidates[0] ?? '';
    const size = fallbackSpriteSize(id);
    drawFallbackSprite(this.context, id, x, y, size.width, size.height);
  }

  spriteClipped(id: string, x: number, y: number, clipWidth: number): void {
    if (clipWidth <= 0) return;
    const sprite = this.assets.guiSprite(id);
    if (sprite === null) {
      const size = fallbackSpriteSize(id);
      drawFallbackSprite(this.context, id, x, y, Math.min(clipWidth, size.width), size.height);
      return;
    }
    const width = Math.min(clipWidth, sprite.width);
    this.context.drawImage(sprite.image, 0, 0, width, sprite.height, x, y, width, sprite.height);
  }

  stretched(ids: string | readonly string[], x: number, y: number, width: number, height: number): void {
    const candidates = typeof ids === 'string' ? [ids] : ids;
    const sprite = this.firstSprite(candidates);
    if (sprite === null) {
      drawFallbackSprite(this.context, candidates[0] ?? '', x, y, width, height);
      return;
    }
    if (sprite.width === width && sprite.height === height) {
      this.context.drawImage(sprite.image, x, y);
      return;
    }
    const border = sprite.nineSlice === null ? halvesBorder(sprite, width, height) : clampBorder(sprite.nineSlice, width, height);
    this.nineSlice(sprite, border, x, y, width, height);
  }

  private nineSlice(sprite: GuiSprite, border: NineSliceBorder, x: number, y: number, width: number, height: number): void {
    const sourceColumns = [
      { from: 0, size: border.left },
      { from: border.left, size: sprite.width - border.left - border.right },
      { from: sprite.width - border.right, size: border.right },
    ];
    const sourceRows = [
      { from: 0, size: border.top },
      { from: border.top, size: sprite.height - border.top - border.bottom },
      { from: sprite.height - border.bottom, size: border.bottom },
    ];
    const targetColumns = [
      { at: x, size: border.left },
      { at: x + border.left, size: width - border.left - border.right },
      { at: x + width - border.right, size: border.right },
    ];
    const targetRows = [
      { at: y, size: border.top },
      { at: y + border.top, size: height - border.top - border.bottom },
      { at: y + height - border.bottom, size: border.bottom },
    ];
    sourceRows.forEach((sourceRow, rowIndex) => {
      const targetRow = targetRows[rowIndex];
      if (targetRow === undefined) return;
      sourceColumns.forEach((sourceColumn, columnIndex) => {
        const targetColumn = targetColumns[columnIndex];
        if (targetColumn === undefined) return;
        this.tile(sprite.image, sourceColumn.from, sourceRow.from, sourceColumn.size, sourceRow.size, targetColumn.at, targetRow.at, targetColumn.size, targetRow.size);
      });
    });
  }

  private tile(image: ImageSource, sourceX: number, sourceY: number, sourceWidth: number, sourceHeight: number, x: number, y: number, width: number, height: number): void {
    if (sourceWidth <= 0 || sourceHeight <= 0 || width <= 0 || height <= 0) return;
    for (let offsetY = 0; offsetY < height; offsetY += sourceHeight) {
      const rowHeight = Math.min(sourceHeight, height - offsetY);
      for (let offsetX = 0; offsetX < width; offsetX += sourceWidth) {
        const columnWidth = Math.min(sourceWidth, width - offsetX);
        this.context.drawImage(image, sourceX, sourceY, columnWidth, rowHeight, x + offsetX, y + offsetY, columnWidth, rowHeight);
      }
    }
  }

  textWidth(text: string, bold = false): number {
    return this.assets.font().measure(text, bold);
  }

  text(text: string, x: number, y: number, options: TextOptions = {}): number {
    return this.assets.font().draw(this.context, text, x, y, {
      color: options.color ?? GUI_COLORS.white,
      shadow: options.shadow ?? true,
      bold: options.bold ?? false,
      italic: options.italic ?? false,
    });
  }

  centeredText(text: string, centerX: number, y: number, options: TextOptions = {}): void {
    this.text(text, Math.floor(centerX - this.textWidth(text, options.bold) / 2), y, options);
  }

  outlinedText(text: string, x: number, y: number, rgb: number): void {
    const plain = { color: 0x000000, shadow: false };
    this.text(text, x + 1, y, plain);
    this.text(text, x - 1, y, plain);
    this.text(text, x, y + 1, plain);
    this.text(text, x, y - 1, plain);
    this.text(text, x, y, { color: rgb, shadow: false });
  }

  item(itemKey: string, x: number, y: number, count: number, enchanted: boolean): void {
    const icon = this.assets.itemIcon(itemKey);
    if (icon === null) {
      this.missingTexture(x, y);
    } else {
      this.context.drawImage(enchanted ? glinted(icon) : icon, x, y, ITEM_SIZE, ITEM_SIZE);
    }
    if (count > 1) {
      const label = String(count);
      this.text(label, x + 19 - 2 - this.textWidth(label), y + 6 + 3);
    }
  }

  missingTexture(x: number, y: number): void {
    this.fill(x, y, ITEM_SIZE, ITEM_SIZE, 0xff000000);
    this.fill(x, y, MISSING_HALF, MISSING_HALF, 0xfff800f8);
    this.fill(x + MISSING_HALF, y + MISSING_HALF, MISSING_HALF, MISSING_HALF, 0xfff800f8);
  }

  image(image: ImageSource, sourceX: number, sourceY: number, sourceWidth: number, sourceHeight: number, x: number, y: number, width: number, height: number): void {
    this.context.drawImage(image, sourceX, sourceY, sourceWidth, sourceHeight, x, y, width, height);
  }

  spriteOrTexture(spriteId: string, texturePath: string, x: number, y: number): boolean {
    const sprite = this.assets.guiSprite(spriteId);
    if (sprite !== null) {
      this.context.drawImage(sprite.image, x, y);
      return true;
    }
    const texture = this.assets.texture(texturePath);
    if (texture === null) return false;
    this.context.drawImage(texture, x, y, ITEM_SIZE, ITEM_SIZE);
    return true;
  }

  inventoryBackground(x: number, y: number): void {
    const texture = this.assets.texture('gui/container/inventory');
    if (texture === null) {
      drawFallbackInventory(this.context, x, y);
      return;
    }
    this.context.drawImage(texture, 0, 0, 176, 166, x, y, 176, 166);
  }

  tooltipBox(x: number, y: number, width: number, height: number): void {
    this.fill(x - 3, y - 4, width + 6, 1, TOOLTIP_BACKGROUND);
    this.fill(x - 3, y + height + 3, width + 6, 1, TOOLTIP_BACKGROUND);
    this.fill(x - 3, y - 3, width + 6, height + 6, TOOLTIP_BACKGROUND);
    this.fill(x - 4, y - 3, 1, height + 6, TOOLTIP_BACKGROUND);
    this.fill(x + width + 3, y - 3, 1, height + 6, TOOLTIP_BACKGROUND);
    this.gradient(x - 3, y - 2, 1, height + 4, TOOLTIP_BORDER_TOP, TOOLTIP_BORDER_BOTTOM);
    this.gradient(x + width + 2, y - 2, 1, height + 4, TOOLTIP_BORDER_TOP, TOOLTIP_BORDER_BOTTOM);
    this.fill(x - 3, y - 3, width + 6, 1, TOOLTIP_BORDER_TOP);
    this.fill(x - 3, y + height + 2, width + 6, 1, TOOLTIP_BORDER_BOTTOM);
  }
}

export interface TooltipLine {
  readonly text: string;
  readonly color: number;
}

export const TOOLTIP_PADDING = 4;

export type TextMeasure = (text: string) => number;

export function tooltipSize(measure: TextMeasure, lines: readonly TooltipLine[]): { readonly width: number; readonly height: number } {
  const width = Math.max(0, ...lines.map((line) => measure(line.text)));
  const height = lines.length === 0 ? 0 : 8 + (lines.length - 1) * TOOLTIP_LINE_HEIGHT + (lines.length > 1 ? 2 : 0);
  return { width: width + TOOLTIP_PADDING * 2, height: height + TOOLTIP_PADDING * 2 };
}

export function drawTooltip(painter: GuiPainter, lines: readonly TooltipLine[]): void {
  const { width, height } = tooltipSize((text) => painter.textWidth(text), lines);
  painter.tooltipBox(TOOLTIP_PADDING, TOOLTIP_PADDING, width - TOOLTIP_PADDING * 2, height - TOOLTIP_PADDING * 2);
  lines.forEach((line, index) => {
    painter.text(line.text, TOOLTIP_PADDING, TOOLTIP_PADDING + index * TOOLTIP_LINE_HEIGHT + (index > 0 ? 2 : 0), { color: line.color });
  });
}
