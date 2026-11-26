export function rgbCss(rgb: number, alpha = 1): string {
  const red = (rgb >> 16) & 0xff;
  const green = (rgb >> 8) & 0xff;
  const blue = rgb & 0xff;
  return `rgba(${red}, ${green}, ${blue}, ${alpha})`;
}

export function argbCss(argb: number): string {
  const alpha = ((argb >>> 24) & 0xff) / 255;
  return rgbCss(argb & 0xffffff, alpha);
}

export function shadowOf(rgb: number): number {
  const red = ((rgb >> 16) & 0xff) >> 2;
  const green = ((rgb >> 8) & 0xff) >> 2;
  const blue = (rgb & 0xff) >> 2;
  return (red << 16) | (green << 8) | blue;
}

export function parseHexColor(hex: string, fallback: number): number {
  const match = /^#?([0-9a-f]{6})$/i.exec(hex.trim());
  return match?.[1] === undefined ? fallback : Number.parseInt(match[1], 16);
}

export const GUI_COLORS = {
  white: 0xffffff,
  disabledText: 0xa0a0a0,
  containerTitle: 0x404040,
  experience: 0x80ff20,
  scoreboardNumber: 0xffff55,
  gray: 0xaaaaaa,
  darkGray: 0x555555,
  aqua: 0x55ffff,
  red: 0xff5555,
  darkRed: 0xaa0000,
  gold: 0xffaa00,
  green: 0x55ff55,
  yellow: 0xffff55,
} as const;
