import type { CSSProperties } from 'react';

export const PUBLIC_FONT_IDS = ['system', 'inter', 'nunito', 'lora', 'space-grotesk'] as const;
export const PUBLIC_RADIUS_IDS = ['none', 'small', 'medium', 'large'] as const;
export const PUBLIC_SCHEME_DEFAULTS = ['system', 'light', 'dark'] as const;

export type PublicFontId = (typeof PUBLIC_FONT_IDS)[number];
export type PublicRadiusId = (typeof PUBLIC_RADIUS_IDS)[number];
export type PublicSchemeDefault = (typeof PUBLIC_SCHEME_DEFAULTS)[number];
export type ColorScheme = 'light' | 'dark';

export interface PublicTheme {
  primaryColor: string;
  accentColor: string;
  backgroundColor: string;
  backgroundImageUrl: string;
  font: PublicFontId;
  radius: PublicRadiusId;
  colorScheme: PublicSchemeDefault;
}

export const DEFAULT_PUBLIC_THEME: PublicTheme = {
  primaryColor: '',
  accentColor: '',
  backgroundColor: '',
  backgroundImageUrl: '',
  font: 'system',
  radius: 'medium',
  colorScheme: 'system',
};

export const DEFAULT_THEME_COLORS = {
  primary: '207 90% 54%',
  accent: '273 68% 59%',
  background: '220 6% 95%',
  lightForeground: '222.2 84% 4.9%',
} as const;

export type PublicThemeVariables = Readonly<Record<string, string>>;

interface Rgb {
  r: number;
  g: number;
  b: number;
}

interface Hsl {
  h: number;
  s: number;
  l: number;
}

const HEX_COLOR = /^#[0-9a-f]{6}$/i;
const HSL_TRIPLET = /^(\d+(?:\.\d+)?) (\d+(?:\.\d+)?)% (\d+(?:\.\d+)?)%$/;
const WHITE_TEXT = '0 0% 100%';
const LIGHT_BACKGROUND_MIN_LUMINANCE = 0.85;
const DARK_BACKGROUND_MAX_LUMINANCE = 0.012;
const LIGHTNESS_SEARCH_STEPS = 24;
const BACKDROP_SCRIM = 0.82;
const PAGE_SELECTOR = '[data-modl="page"]';

const FONT_STACKS: Readonly<Record<Exclude<PublicFontId, 'system'>, string>> = {
  inter: '"Inter Variable", ui-sans-serif, system-ui, sans-serif',
  nunito: '"Nunito Variable", ui-rounded, ui-sans-serif, system-ui, sans-serif',
  lora: '"Lora Variable", ui-serif, Georgia, serif',
  'space-grotesk': '"Space Grotesk Variable", ui-sans-serif, system-ui, sans-serif',
};

const RADII: Readonly<Record<Exclude<PublicRadiusId, 'medium'>, string>> = {
  none: '0rem',
  small: '0.375rem',
  large: '1.25rem',
};

export function isHexColor(value: string): boolean {
  return HEX_COLOR.test(value);
}

function hexToRgb(hex: string): Rgb {
  const value = Number.parseInt(hex.slice(1), 16);
  return { r: ((value >> 16) & 0xff) / 255, g: ((value >> 8) & 0xff) / 255, b: (value & 0xff) / 255 };
}

function channelHex(channel: number): string {
  return Math.round(Math.min(1, Math.max(0, channel)) * 255).toString(16).padStart(2, '0');
}

function rgbToHex({ r, g, b }: Rgb): string {
  return `#${channelHex(r)}${channelHex(g)}${channelHex(b)}`;
}

function rgbToHsl({ r, g, b }: Rgb): Hsl {
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const l = (max + min) / 2;
  const delta = max - min;
  if (delta === 0) return { h: 0, s: 0, l };
  const s = delta / (1 - Math.abs(2 * l - 1));
  const sector = max === r ? ((g - b) / delta) % 6 : max === g ? (b - r) / delta + 2 : (r - g) / delta + 4;
  return { h: (sector * 60 + 360) % 360, s, l };
}

function hslToRgb({ h, s, l }: Hsl): Rgb {
  const amplitude = s * Math.min(l, 1 - l);
  const channel = (offset: number) => {
    const position = (offset + h / 30) % 12;
    return l - amplitude * Math.max(-1, Math.min(position - 3, 9 - position, 1));
  };
  return { r: channel(0), g: channel(8), b: channel(4) };
}

function parseTriplet(triplet: string): Hsl {
  const match = HSL_TRIPLET.exec(triplet);
  if (match === null) throw new Error(`Not an HSL triplet: ${triplet}`);
  return { h: Number(match[1]), s: Number(match[2]) / 100, l: Number(match[3]) / 100 };
}

function rounded(value: number): number {
  return Math.round(value * 10) / 10;
}

function toTriplet({ h, s, l }: Hsl): string {
  return `${rounded(h)} ${rounded(s * 100)}% ${rounded(l * 100)}%`;
}

function linearChannel(channel: number): number {
  return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
}

function relativeLuminance({ r, g, b }: Rgb): number {
  return 0.2126 * linearChannel(r) + 0.7152 * linearChannel(g) + 0.0722 * linearChannel(b);
}

export function contrastRatio(first: number, second: number): number {
  return (Math.max(first, second) + 0.05) / (Math.min(first, second) + 0.05);
}

export function tripletToHex(triplet: string): string {
  return rgbToHex(hslToRgb(parseTriplet(triplet)));
}

export function luminanceOfHex(hex: string): number {
  return relativeLuminance(hexToRgb(hex));
}

export function readableTextOn(hex: string): string {
  const fill = luminanceOfHex(hex);
  const darkText = relativeLuminance(hslToRgb(parseTriplet(DEFAULT_THEME_COLORS.lightForeground)));
  return contrastRatio(fill, 1) >= contrastRatio(fill, darkText) ? WHITE_TEXT : DEFAULT_THEME_COLORS.lightForeground;
}

function boundaryLightness(failing: number, passing: number, passes: (lightness: number) => boolean): number {
  let fail = failing;
  let pass = passing;
  for (let step = 0; step < LIGHTNESS_SEARCH_STEPS; step++) {
    const middle = (fail + pass) / 2;
    if (passes(middle)) {
      pass = middle;
    } else {
      fail = middle;
    }
  }
  return pass;
}

export function backgroundFor(hex: string, scheme: ColorScheme): string {
  const hsl = rgbToHsl(hexToRgb(hex));
  const luminanceAt = (lightness: number) => relativeLuminance(hslToRgb({ ...hsl, l: lightness }));
  const readable = scheme === 'light'
    ? (lightness: number) => luminanceAt(lightness) >= LIGHT_BACKGROUND_MIN_LUMINANCE
    : (lightness: number) => luminanceAt(lightness) <= DARK_BACKGROUND_MAX_LUMINANCE;
  if (readable(hsl.l)) return toTriplet(hsl);
  return toTriplet({ ...hsl, l: boundaryLightness(hsl.l, scheme === 'light' ? 1 : 0, readable) });
}

function colorVariables(hex: string, fill: string, text: string): Record<string, string> {
  return { [fill]: toTriplet(rgbToHsl(hexToRgb(hex))), [text]: readableTextOn(hex) };
}

export function publicThemeVariables(theme: PublicTheme, scheme: ColorScheme): PublicThemeVariables {
  const variables: Record<string, string> = {};
  if (isHexColor(theme.primaryColor)) {
    Object.assign(variables, colorVariables(theme.primaryColor, '--primary', '--primary-foreground'));
    variables['--ring'] = toTriplet(rgbToHsl(hexToRgb(theme.primaryColor)));
  }
  if (isHexColor(theme.accentColor)) {
    Object.assign(variables, colorVariables(theme.accentColor, '--accent', '--accent-foreground'));
  }
  if (isHexColor(theme.backgroundColor)) {
    variables['--background'] = backgroundFor(theme.backgroundColor, scheme);
  }
  if (theme.radius !== 'medium') {
    variables['--radius'] = RADII[theme.radius];
  }
  if (theme.font !== 'system') {
    variables['--font-sans'] = FONT_STACKS[theme.font];
  }
  return variables;
}

export function cssString(value: string): string {
  return `"${value.replace(/[\\"]/g, (character) => `\\${character}`).replace(/[\n\r\f]/g, ' ')}"`;
}

function backdropImage(theme: PublicTheme): string | null {
  if (theme.backgroundImageUrl === '') return null;
  const scrim = `hsl(var(--background) / ${String(BACKDROP_SCRIM)})`;
  return `linear-gradient(${scrim}, ${scrim}), url(${cssString(theme.backgroundImageUrl)})`;
}

export function publicBackdropStyle(theme: PublicTheme): CSSProperties | undefined {
  const image = backdropImage(theme);
  if (image === null) return undefined;
  return { backgroundImage: image, backgroundSize: 'cover', backgroundPosition: 'center' };
}

export function publicThemeRules(theme: PublicTheme, scheme: ColorScheme): readonly string[] {
  const rules: string[] = [];
  const declarations = Object.entries(publicThemeVariables(theme, scheme)).map(([name, value]) => `${name}: ${value};`);
  if (declarations.length > 0) rules.push(`:root { ${declarations.join(' ')} }`);
  const image = backdropImage(theme);
  if (image !== null) {
    rules.push(`${PAGE_SELECTOR} { isolation: isolate; }`);
    rules.push(`${PAGE_SELECTOR}::before { content: ""; position: fixed; inset: 0; z-index: -1; pointer-events: none; `
      + `background-image: ${image}; background-size: cover; background-position: center; }`);
  }
  return rules;
}

export function resolvePublicColorScheme(visitor: ColorScheme | null, ownerDefault: PublicSchemeDefault, system: ColorScheme): ColorScheme {
  if (visitor !== null) return visitor;
  return ownerDefault === 'system' ? system : ownerDefault;
}
