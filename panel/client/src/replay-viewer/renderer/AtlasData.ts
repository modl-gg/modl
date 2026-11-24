import { z } from 'zod';
import type { AtlasItem, AtlasMapping, AtlasUV, BlockFaces } from '../types/AtlasMapping';

const MAX_STATE_ID = 0xfffff;

const relativeAssetPathSchema = z.string().regex(/^(?!.*\.\.)[\w-][\w.-]*(?:\/[\w.-]+)*$/);
const stateIdSchema = z.number().int().min(0).max(MAX_STATE_ID);

const blockSchema = z.object({
  id: z.number().int(),
  name: z.string(),
  minState: stateIdSchema,
  maxState: stateIdSchema,
  transparent: z.boolean().exactOptional(),
  faces: z
    .object({
      top: z.string(),
      side: z.string(),
      bottom: z.string(),
      shape: z.enum(['cross', 'floor', 'multiface']).exactOptional(),
    })
    .exactOptional(),
  faceMasks: z.array(z.number().int()).exactOptional(),
});

const atlasDataSchema = z.object({
  gridSize: z.number().int().positive(),
  textures: z.array(z.string()),
  blocks: z.array(blockSchema),
  waterLevels: z.record(z.string(), z.number()),
  items: z.record(z.string(), z.string().nullable()),
  armorTextures: z.record(
    z.string(),
    z.object({
      layer1: relativeAssetPathSchema.exactOptional(),
      layer2: relativeAssetPathSchema.exactOptional(),
    }),
  ),
  entityTextures: z.record(z.string(), relativeAssetPathSchema),
});

type AtlasBlock = z.infer<typeof blockSchema>;
type AtlasData = z.infer<typeof atlasDataSchema>;

function textureGrid(gridSize: number, names: readonly string[]): Record<string, AtlasUV> {
  const tileSpan = 1 / gridSize;
  return Object.fromEntries(
    names.map((name, index) => [
      name,
      {
        u: (index % gridSize) / gridSize,
        v: Math.floor(index / gridSize) / gridSize,
        su: tileSpan,
        sv: tileSpan,
      },
    ]),
  );
}

function statesOf({ minState, maxState }: AtlasBlock): number[] {
  return Array.from({ length: maxState - minState + 1 }, (_, offset) => minState + offset);
}

function facesByState(block: AtlasBlock): [number, BlockFaces][] {
  const { faces, faceMasks } = block;
  if (!faces) return [];
  return statesOf(block).map((state, offset) => {
    const faceMask = faceMasks?.[offset];
    return [state, faceMask === undefined ? faces : { ...faces, faceMask }];
  });
}

function itemsByName(
  itemTextures: AtlasData['items'],
  blocks: readonly AtlasBlock[],
): Record<string, AtlasItem> {
  const blockNames = new Set(blocks.map((block) => block.name));
  return Object.fromEntries(
    Object.entries(itemTextures).map(([name, texture]) => [name, { texture, isBlock: blockNames.has(name) }]),
  );
}

function toAtlasMapping(data: AtlasData): AtlasMapping {
  const { gridSize, textures, blocks, items, waterLevels, armorTextures, entityTextures } = data;
  return {
    textures: textureGrid(gridSize, textures),
    blocks: Object.fromEntries(blocks.flatMap(facesByState)),
    transparentStates: blocks.filter((block) => block.transparent).flatMap(statesOf),
    stateToBlock: Object.fromEntries(blocks.flatMap((block) => statesOf(block).map((state) => [state, block.id]))),
    waterLevels,
    blockNameToState: Object.fromEntries(
      blocks.filter((block) => block.faces).map((block) => [block.name, block.minState]),
    ),
    items: itemsByName(items, blocks),
    armorTextures,
    entityTextures,
  };
}

export const atlasMappingSchema = atlasDataSchema.transform(toAtlasMapping);
