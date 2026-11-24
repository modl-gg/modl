import { ITEM_TEXTURE_FALLBACKS, type AtlasMapping, type AtlasUV } from '../types/AtlasMapping';

export interface AtlasInfo extends Pick<AtlasMapping, 'textures' | 'items' | 'blockNameToState' | 'blocks'> {
  readonly atlasImage: HTMLImageElement;
}

export function formatItemName(name: string): string {
  return name.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
}

export function itemColor(name: string): string {
  let hash = 0;
  for (let i = 0; i < name.length; i++) {
    hash = ((hash << 5) - hash + name.charCodeAt(i)) | 0;
  }
  const hue = Math.abs(hash) % 360;
  return `hsl(${hue}, 40%, 35%)`;
}

function textureOf(name: string | null | undefined, atlas: AtlasInfo): AtlasUV | undefined {
  return name ? atlas.textures[name] : undefined;
}

function blockSideTexture(itemName: string, atlas: AtlasInfo): AtlasUV | undefined {
  const stateId = atlas.blockNameToState?.[itemName];
  const blockData = stateId === undefined ? undefined : atlas.blocks[stateId];
  return blockData ? textureOf(blockData.side || blockData.top, atlas) : undefined;
}

export function getItemUV(itemName: string, atlas: AtlasInfo): AtlasUV | null {
  return textureOf(atlas.items?.[itemName]?.texture, atlas)
    ?? atlas.textures[`item/${itemName}`]
    ?? atlas.textures[`block/${itemName}`]
    ?? blockSideTexture(itemName, atlas)
    ?? textureOf(ITEM_TEXTURE_FALLBACKS[itemName], atlas)
    ?? null;
}
