export type GuiScaleChoice = 'auto' | number;

const MAX_SCALE = 16;
const WIDE_MIN_WIDTH = 320;
const WIDE_MIN_HEIGHT = 240;
const NARROW_VIEWPORT_WIDTH = 640;
const NARROW_MIN_WIDTH = 180;
const NARROW_MIN_HEIGHT = 200;

export interface GuiViewport {
  readonly cssWidth: number;
  readonly cssHeight: number;
  readonly pixelRatio: number;
}

export interface GuiFrame {
  readonly deviceScale: number;
  readonly cssScale: number;
  readonly width: number;
  readonly height: number;
}

function fittingScale(width: number, height: number, minWidth: number, minHeight: number): number {
  let scale = 1;
  while (scale < MAX_SCALE && width / (scale + 1) >= minWidth && height / (scale + 1) >= minHeight) {
    scale += 1;
  }
  return scale;
}

function deviceSize(viewport: GuiViewport): { readonly width: number; readonly height: number } {
  return { width: Math.floor(viewport.cssWidth * viewport.pixelRatio), height: Math.floor(viewport.cssHeight * viewport.pixelRatio) };
}

export function autoGuiScale(viewport: GuiViewport): number {
  const device = deviceSize(viewport);
  return viewport.cssWidth < NARROW_VIEWPORT_WIDTH
    ? fittingScale(device.width, device.height, NARROW_MIN_WIDTH, NARROW_MIN_HEIGHT)
    : fittingScale(device.width, device.height, WIDE_MIN_WIDTH, WIDE_MIN_HEIGHT);
}

export function maxGuiScale(viewport: GuiViewport): number {
  const device = deviceSize(viewport);
  return fittingScale(device.width, device.height, NARROW_MIN_WIDTH, NARROW_MIN_HEIGHT);
}

export function guiFrame(viewport: GuiViewport, choice: GuiScaleChoice): GuiFrame {
  const deviceScale = choice === 'auto' ? autoGuiScale(viewport) : Math.max(1, Math.min(choice, maxGuiScale(viewport)));
  const device = deviceSize(viewport);
  return {
    deviceScale,
    cssScale: deviceScale / viewport.pixelRatio,
    width: Math.max(1, Math.floor(device.width / deviceScale)),
    height: Math.max(1, Math.floor(device.height / deviceScale)),
  };
}

export function nextGuiScaleChoice(choice: GuiScaleChoice, viewport: GuiViewport): GuiScaleChoice {
  if (choice === 'auto') return 1;
  return choice >= maxGuiScale(viewport) ? 'auto' : choice + 1;
}

export function parseGuiScaleChoice(value: string | null): GuiScaleChoice {
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed >= 1 && parsed <= MAX_SCALE ? parsed : 'auto';
}
