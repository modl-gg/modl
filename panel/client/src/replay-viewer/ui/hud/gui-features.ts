import { atLeast, parseVersion } from '../../format/MinecraftVersion';

export interface GuiFeatures {
  readonly offhand: boolean;
  readonly recipeBook: boolean;
}

export function guiFeatures(version: string): GuiFeatures {
  const parsed = parseVersion(version);
  return { offhand: atLeast(parsed, '1.9'), recipeBook: atLeast(parsed, '1.12') };
}
