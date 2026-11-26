import { type MinecraftVersion, atLeast } from '../../format/MinecraftVersion.ts';
import { crc32 } from './crc32.ts';

export const MODEL_SOURCE_FOR_LEGACY = '1.13.2';

export type SourceRole = 'primary' | 'models';

export interface SourceJar {
  readonly version: string;
  readonly role: SourceRole;
  readonly accepts: (name: string) => boolean;
}

const SELECTION_LOGIC_VERSION = 1;
const ASSET_PREFIX = 'assets/minecraft/';
const BIOME_PREFIX = 'data/minecraft/worldgen/biome/';
const BIOME_DIRECTORY = 'biome/';
const MODEL_SOURCE_TEXTURES = ['textures/block/', 'textures/item/'];
const FIRST_FLATTENED_VERSION = '1.13';

const MODEL_DIRECTORIES = ['blockstates/', 'models/', 'items/', 'equipment/'];
const TEXTURE_DIRECTORIES = [
  'textures/block/',
  'textures/blocks/',
  'textures/item/',
  'textures/items/',
  'textures/entity/',
  'textures/colormap/',
  'textures/models/armor/',
  'textures/mob_effect/',
  'textures/gui/sprites/hud/',
  'textures/gui/sprites/widget/',
  'textures/gui/sprites/container/slot',
  'textures/gui/sprites/container/inventory/',
  'textures/gui/sprites/recipe_book/',
];
const SINGLE_FILES = [
  'textures/gui/widgets.png',
  'textures/gui/icons.png',
  'textures/gui/container/inventory.png',
  'textures/font/ascii.png',
  'textures/font/nonlatin_european.png',
  'textures/font/accented.png',
  'lang/en_us.json',
  'lang/en_US.lang',
];
const FONT_DEFINITIONS = /^font\/(include\/)?[a-z_]+\.json$/;
const ASSET_EXTENSION = /\.(png|json|mcmeta|lang)$/;

function primaryAsset(path: string): boolean {
  if (!ASSET_EXTENSION.test(path)) return false;
  return MODEL_DIRECTORIES.some((directory) => path.startsWith(directory))
    || TEXTURE_DIRECTORIES.some((directory) => path.startsWith(directory))
    || SINGLE_FILES.includes(path)
    || FONT_DEFINITIONS.test(path);
}

function legacyPrimaryAsset(path: string): boolean {
  return primaryAsset(path) && !MODEL_DIRECTORIES.some((directory) => path.startsWith(directory));
}

function modelSourceAsset(path: string): boolean {
  return ASSET_EXTENSION.test(path) && [...MODEL_DIRECTORIES, ...MODEL_SOURCE_TEXTURES].some((directory) => path.startsWith(directory));
}

export function assetPath(entryName: string): string | null {
  if (entryName.startsWith(ASSET_PREFIX)) return entryName.slice(ASSET_PREFIX.length);
  if (entryName.startsWith(BIOME_PREFIX) && entryName.endsWith('.json')) return `${BIOME_DIRECTORY}${entryName.slice(BIOME_PREFIX.length)}`;
  return null;
}

function acceptingAssets(test: (path: string) => boolean, biomes: boolean): (name: string) => boolean {
  return (name) => {
    const path = assetPath(name);
    if (path === null) return false;
    if (path.startsWith(BIOME_DIRECTORY)) return biomes && !path.slice(BIOME_DIRECTORY.length).includes('/');
    return test(path);
  };
}

export function isLegacyVersion(version: MinecraftVersion): boolean {
  return !atLeast(version, FIRST_FLATTENED_VERSION);
}

export function sourceJars(version: MinecraftVersion): SourceJar[] {
  if (isLegacyVersion(version)) {
    return [
      { version: version.id, role: 'primary', accepts: acceptingAssets(legacyPrimaryAsset, false) },
      { version: MODEL_SOURCE_FOR_LEGACY, role: 'models', accepts: acceptingAssets(modelSourceAsset, false) },
    ];
  }
  return [{ version: version.id, role: 'primary', accepts: acceptingAssets(primaryAsset, true) }];
}

export const SELECTION_REVISION = crc32(new TextEncoder().encode(JSON.stringify([
  SELECTION_LOGIC_VERSION,
  FIRST_FLATTENED_VERSION,
  MODEL_SOURCE_FOR_LEGACY,
  BIOME_DIRECTORY,
  MODEL_SOURCE_TEXTURES,
  ASSET_PREFIX,
  BIOME_PREFIX,
  MODEL_DIRECTORIES,
  TEXTURE_DIRECTORIES,
  SINGLE_FILES,
  FONT_DEFINITIONS.source,
  ASSET_EXTENSION.source,
]))).toString(16);
