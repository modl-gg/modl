import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  DEFAULT_PUBLIC_THEME,
  DEFAULT_THEME_COLORS,
  backgroundFor,
  contrastRatio,
  cssString,
  luminanceOfHex,
  publicBackdropStyle,
  publicThemeRules,
  publicThemeVariables,
  readableTextOn,
  resolvePublicColorScheme,
  tripletToHex,
  type PublicTheme,
} from './public-theme.ts';

const LIGHT_FOREGROUND_HEX = tripletToHex(DEFAULT_THEME_COLORS.lightForeground);
const DARK_FOREGROUND_LUMINANCE = luminanceOfHex('#fafafa');

function theme(overrides: Partial<PublicTheme>): PublicTheme {
  return { ...DEFAULT_PUBLIC_THEME, ...overrides };
}

function luminanceOfTriplet(triplet: string): number {
  return luminanceOfHex(tripletToHex(triplet));
}

await test('the default theme leaves every panel variable untouched', () => {
  assert.deepEqual(publicThemeVariables(DEFAULT_PUBLIC_THEME, 'light'), {});
  assert.deepEqual(publicThemeVariables(DEFAULT_PUBLIC_THEME, 'dark'), {});
  assert.equal(publicBackdropStyle(DEFAULT_PUBLIC_THEME), undefined);
  assert.deepEqual(publicThemeRules(DEFAULT_PUBLIC_THEME, 'dark'), []);
});

await test('the theme becomes one root rule and a fixed backdrop layer under the page', () => {
  const rules = publicThemeRules(theme({ primaryColor: '#ff7a00', backgroundImageUrl: '/v1/storage/objects/x.png' }), 'light');
  assert.equal(rules.length, 3);
  assert.match(rules[0] ?? '', /^:root \{ --primary: [^;]+; --primary-foreground: [^;]+; --ring: [^;]+; \}$/);
  assert.match(rules[2] ?? '', /^\[data-modl="page"\]::before \{ .*position: fixed; .*url\("\/v1\/storage\/objects\/x\.png"\)/);
});

await test('the default colors match the panel stylesheet', () => {
  const stylesheet = readFileSync(new URL('../index.css', import.meta.url), 'utf8');
  const root = stylesheet.slice(stylesheet.indexOf(':root'), stylesheet.indexOf('.dark {'));
  assert.match(root, new RegExp(`--primary: ${DEFAULT_THEME_COLORS.primary};`));
  assert.match(root, new RegExp(`--accent: ${DEFAULT_THEME_COLORS.accent};`));
  assert.match(root, new RegExp(`--background: ${DEFAULT_THEME_COLORS.background};`));
  assert.match(root, new RegExp(`--foreground: ${DEFAULT_THEME_COLORS.lightForeground.replaceAll('.', '\\.')};`));
});

await test('a brand color sets primary, ring and a readable foreground', () => {
  const variables = publicThemeVariables(theme({ primaryColor: '#ffcc00' }), 'light');
  assert.equal(variables['--primary'], variables['--ring']);
  assert.equal(variables['--primary-foreground'], DEFAULT_THEME_COLORS.lightForeground);
  assert.equal(publicThemeVariables(theme({ primaryColor: '#1d4ed8' }), 'dark')['--primary-foreground'], '0 0% 100%');
});

await test('text on a brand color is whichever of white and the panel text contrasts more', () => {
  const darkText = luminanceOfHex(LIGHT_FOREGROUND_HEX);
  for (const color of ['#000000', '#ffffff', '#ff0000', '#00ff00', '#0000ff', '#808080', '#ff8800', '#7c3aed']) {
    const fill = luminanceOfHex(color);
    const chosen = readableTextOn(color) === '0 0% 100%' ? 1 : darkText;
    const other = chosen === 1 ? darkText : 1;
    assert.ok(contrastRatio(fill, chosen) >= contrastRatio(fill, other), color);
  }
});

await test('a background color becomes a light tint in light mode and a dark shade in dark mode', () => {
  for (const color of ['#0b1020', '#ffffff', '#ff0000', '#f5e6c8', '#3b82f6', '#000000']) {
    const light = luminanceOfTriplet(backgroundFor(color, 'light'));
    const dark = luminanceOfTriplet(backgroundFor(color, 'dark'));
    assert.ok(contrastRatio(light, luminanceOfHex(LIGHT_FOREGROUND_HEX)) >= 12, `${color} light`);
    assert.ok(contrastRatio(dark, DARK_FOREGROUND_LUMINANCE) >= 12, `${color} dark`);
  }
});

await test('a background that is already light or dark enough is kept as it is', () => {
  assert.equal(tripletToHex(backgroundFor('#f8fafc', 'light')), '#f8fafc');
  assert.equal(tripletToHex(backgroundFor('#09090b', 'dark')), '#09090b');
});

await test('radius and font override only when they differ from the panel', () => {
  const variables = publicThemeVariables(theme({ radius: 'none', font: 'lora' }), 'light');
  assert.equal(variables['--radius'], '0rem');
  assert.match(variables['--font-sans'] ?? '', /^"Lora Variable"/);
});

await test('a background image is quoted for CSS', () => {
  assert.equal(cssString('/a"b\\c.png'), '"/a\\"b\\\\c.png"');
  const style = publicBackdropStyle(theme({ backgroundImageUrl: '/v1/storage/objects/x.png' }));
  assert.match(String(style?.backgroundImage), /url\("\/v1\/storage\/objects\/x\.png"\)$/);
});

await test('the visitor choice wins over the owner default, which wins over the system', () => {
  assert.equal(resolvePublicColorScheme('light', 'dark', 'dark'), 'light');
  assert.equal(resolvePublicColorScheme(null, 'dark', 'light'), 'dark');
  assert.equal(resolvePublicColorScheme(null, 'system', 'dark'), 'dark');
});
