import type { BitmapFont, DrawingContext, TextStyle } from './AssetLibrary.ts';
import { bareName } from '../format/ids.ts';
import { arrayField, isRecord, numberField, stringField } from './json.ts';

export interface FontSheet {
  readonly id: string;
  readonly image: ImageBitmap;
  readonly pixels: Uint8ClampedArray;
}

export interface BitmapProvider {
  readonly file: string;
  readonly ascent: number;
  readonly height: number;
  readonly chars: readonly string[];
}

interface Glyph {
  readonly sheet: FontSheet;
  readonly x: number;
  readonly y: number;
  readonly cellWidth: number;
  readonly cellHeight: number;
  readonly width: number;
  readonly scale: number;
  readonly ascent: number;
}

const MAX_REFERENCE_DEPTH = 8;
const SPACE_ADVANCE = 4;
const LINE_ASCENT = 7;
const RUN_PADDING = 4;

export function fontProviders(readJson: (path: string) => unknown, id = 'default', depth = 0): { bitmaps: BitmapProvider[]; spaces: Map<string, number> } {
  const bitmaps: BitmapProvider[] = [];
  const spaces = new Map<string, number>();
  if (depth > MAX_REFERENCE_DEPTH) return { bitmaps, spaces };
  const json = readJson(`font/${bareName(id)}.json`);
  if (!isRecord(json)) return { bitmaps, spaces };
  for (const provider of arrayField(json, 'providers')) {
    if (!isRecord(provider)) continue;
    const type = bareName(stringField(provider, 'type') ?? '');
    if (type === 'reference') {
      const reference = stringField(provider, 'id');
      if (reference === null) continue;
      const nested = fontProviders(readJson, reference, depth + 1);
      bitmaps.push(...nested.bitmaps);
      for (const [character, advance] of nested.spaces) spaces.set(character, advance);
    } else if (type === 'bitmap') {
      const file = stringField(provider, 'file');
      if (file === null) continue;
      bitmaps.push({
        file: bareName(file),
        ascent: numberField(provider, 'ascent') ?? LINE_ASCENT,
        height: numberField(provider, 'height') ?? 8,
        chars: arrayField(provider, 'chars').filter((row): row is string => typeof row === 'string'),
      });
    } else if (type === 'space') {
      const advances = provider['advances'];
      if (!isRecord(advances)) continue;
      for (const [character, advance] of Object.entries(advances)) {
        if (typeof advance === 'number') spaces.set(character, advance);
      }
    }
  }
  return { bitmaps, spaces };
}

export function legacyAsciiProvider(): BitmapProvider {
  const rows: string[] = [];
  for (let row = 0; row < 16; row++) {
    let text = '';
    for (let column = 0; column < 16; column++) {
      const code = row * 16 + column;
      text += code >= 0x20 && code < 0x7f ? String.fromCharCode(code) : '\u0000';
    }
    rows.push(text);
  }
  return { file: 'font/ascii.png', ascent: LINE_ASCENT, height: 8, chars: rows };
}

function measureColumns(sheet: FontSheet, x: number, y: number, cellWidth: number, cellHeight: number): number {
  const sheetWidth = sheet.image.width;
  for (let column = cellWidth - 1; column >= 0; column--) {
    for (let row = 0; row < cellHeight; row++) {
      if ((sheet.pixels[((y + row) * sheetWidth + x + column) * 4 + 3] ?? 0) > 0) return column + 1;
    }
  }
  return 0;
}

export class BitmapFontImpl implements BitmapFont {
  readonly lineHeight = 9;
  private readonly glyphs = new Map<string, Glyph>();
  private readonly spaces: ReadonlyMap<string, number>;
  private scratch: OffscreenCanvas | null = null;

  constructor(providers: readonly BitmapProvider[], sheets: ReadonlyMap<string, FontSheet>, spaces: ReadonlyMap<string, number>) {
    this.spaces = spaces;
    for (const provider of providers) {
      const sheet = sheets.get(provider.file);
      if (sheet === undefined) continue;
      const rows = provider.chars.length;
      const columns = Math.max(...provider.chars.map((row) => Array.from(row).length), 1);
      if (rows === 0) continue;
      const cellWidth = Math.floor(sheet.image.width / columns);
      const cellHeight = Math.floor(sheet.image.height / rows);
      const scale = provider.height / cellHeight;
      provider.chars.forEach((row, rowIndex) => {
        Array.from(row).forEach((character, columnIndex) => {
          if (character === '\u0000' || character === ' ' || this.glyphs.has(character)) return;
          const x = columnIndex * cellWidth;
          const y = rowIndex * cellHeight;
          const width = measureColumns(sheet, x, y, cellWidth, cellHeight);
          this.glyphs.set(character, { sheet, x, y, cellWidth, cellHeight, width, scale, ascent: provider.ascent });
        });
      });
    }
  }

  private advance(character: string, bold: boolean): number {
    const space = this.spaces.get(character);
    if (space !== undefined) return space;
    if (character === ' ') return SPACE_ADVANCE;
    const glyph = this.glyphs.get(character);
    if (glyph === undefined) return 6 + (bold ? 1 : 0);
    return Math.floor(0.5 + glyph.width * glyph.scale) + 1 + (bold ? 1 : 0);
  }

  measure(text: string, bold = false): number {
    let width = 0;
    for (const character of text) width += this.advance(character, bold);
    return width;
  }

  private scratchFor(width: number, height: number): OffscreenCanvasRenderingContext2D | null {
    if (this.scratch === null || this.scratch.width < width || this.scratch.height < height) {
      this.scratch = new OffscreenCanvas(Math.max(width, this.scratch?.width ?? 0), Math.max(height, this.scratch?.height ?? 0));
    }
    const context = this.scratch.getContext('2d');
    if (context === null) return null;
    context.globalCompositeOperation = 'source-over';
    context.clearRect(0, 0, width, height);
    context.imageSmoothingEnabled = false;
    return context;
  }

  private drawRun(context: DrawingContext, text: string, x: number, y: number, color: number, bold: boolean, italic: boolean): number {
    const runWidth = Math.ceil(this.measure(text, bold)) + 2;
    const runHeight = LINE_ASCENT + RUN_PADDING * 2 + 8;
    const scratch = this.scratchFor(runWidth, runHeight);
    if (scratch === null || this.scratch === null) return runWidth - 2;
    const fill = `#${color.toString(16).padStart(6, '0')}`;
    let cursor = 0;
    for (const character of text) {
      const glyph = this.glyphs.get(character);
      if (glyph !== undefined) {
        const top = RUN_PADDING + LINE_ASCENT - glyph.ascent;
        const width = glyph.width;
        const height = glyph.cellHeight;
        for (let copy = 0; copy < (bold ? 2 : 1); copy++) {
          scratch.drawImage(glyph.sheet.image, glyph.x, glyph.y, width, height, cursor + copy, top, width * glyph.scale, height * glyph.scale);
        }
      } else if (character !== ' ' && !this.spaces.has(character)) {
        scratch.fillStyle = fill;
        scratch.font = '8px monospace';
        scratch.textBaseline = 'alphabetic';
        scratch.fillText(character, cursor, RUN_PADDING + LINE_ASCENT);
      }
      cursor += this.advance(character, bold);
    }
    scratch.globalCompositeOperation = 'source-in';
    scratch.fillStyle = fill;
    scratch.fillRect(0, 0, runWidth, runHeight);
    scratch.globalCompositeOperation = 'source-over';
    if (italic) {
      context.save();
      context.transform(1, 0, -0.25, 1, (y + runHeight) * 0.25, 0);
    }
    context.drawImage(this.scratch, 0, 0, runWidth, runHeight, x, y - RUN_PADDING, runWidth, runHeight);
    if (italic) context.restore();
    return cursor;
  }

  draw(context: DrawingContext, text: string, x: number, y: number, style: TextStyle): number {
    const bold = style.bold === true;
    const italic = style.italic === true;
    if (style.shadow) {
      const shadow = (style.color & 0xfcfcfc) >> 2;
      this.drawRun(context, text, x + 1, y + 1, shadow, bold, italic);
    }
    return this.drawRun(context, text, x, y, style.color & 0xffffff, bold, italic);
  }
}
