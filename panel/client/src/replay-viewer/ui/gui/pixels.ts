import { rgbCss } from './colors';

export interface PixelArt {
  readonly width: number;
  readonly height: number;
  readonly rows: readonly string[];
}

export function pixelArt(rows: readonly string[]): PixelArt {
  return { width: Math.max(...rows.map((row) => row.length)), height: rows.length, rows };
}

export function drawPixelArt(context: CanvasRenderingContext2D, art: PixelArt, x: number, y: number, rgb: number, alpha = 1): void {
  context.fillStyle = rgbCss(rgb, alpha);
  art.rows.forEach((row, rowIndex) => {
    for (let column = 0; column < row.length; column += 1) {
      if (row[column] === '#') context.fillRect(x + column, y + rowIndex, 1, 1);
    }
  });
}

export function fillRect(context: CanvasRenderingContext2D, x: number, y: number, width: number, height: number, css: string): void {
  context.fillStyle = css;
  context.fillRect(x, y, width, height);
}
