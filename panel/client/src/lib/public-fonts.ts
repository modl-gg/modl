import type { PublicFontId } from './public-theme.ts';

const FONT_STYLESHEETS: Readonly<Record<Exclude<PublicFontId, 'system'>, () => Promise<unknown>>> = {
  inter: async () => import('@fontsource-variable/inter'),
  nunito: async () => import('@fontsource-variable/nunito'),
  lora: async () => import('@fontsource-variable/lora'),
  'space-grotesk': async () => import('@fontsource-variable/space-grotesk'),
};

export async function loadPublicFont(font: PublicFontId): Promise<void> {
  if (font === 'system') return;
  await FONT_STYLESHEETS[font]();
}
