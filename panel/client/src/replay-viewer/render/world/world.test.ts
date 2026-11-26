import test from 'node:test';
import assert from 'node:assert/strict';
import { ResourcePack } from '../../assets/ResourcePack.ts';
import { type SpriteImage, buildAtlas } from './Atlas.ts';
import { BlockLibrary } from './BlockLibrary.ts';
import type { BlockState } from '../../format/BlockState.ts';
import { parseBlockStateDefinition, pickWeighted } from './BlockStates.ts';
import { blockInRange, blockIndex, sectionKey, unpackSectionKey } from '../../format/WorldLayout.ts';
import { BlockWorld } from './BlockWorld.ts';
import type { SectionMesh } from './SectionMesher.ts';
import type { Colormaps } from './Tints.ts';
import { WorldAssets, WorldService, blockSource, collectSprites } from './WorldService.ts';

const encoder = new TextEncoder();

function json(value: unknown): Uint8Array<ArrayBuffer> {
  return encoder.encode(JSON.stringify(value));
}

const ALL_FACES = (texture: string, cull: boolean, tint = false): Record<string, unknown> => Object.fromEntries(
  ['down', 'up', 'north', 'south', 'west', 'east'].map((face) => [face, { texture, ...(cull ? { cullface: face } : {}), ...(tint ? { tintindex: 0 } : {}) }]),
);

function syntheticPack(): ResourcePack {
  const files = new Map<string, Uint8Array<ArrayBuffer>>([
    ['models/block/cube_all.json', json({ elements: [{ from: [0, 0, 0], to: [16, 16, 16], faces: ALL_FACES('#all', true) }] })],
    ['models/block/stone.json', json({ parent: 'block/cube_all', textures: { all: 'block/stone' } })],
    ['models/block/grass_block.json', json({ elements: [{ from: [0, 0, 0], to: [16, 16, 16], faces: ALL_FACES('#top', true, true) }], textures: { top: 'block/grass_top' } })],
    ['models/block/slab.json', json({ elements: [{ from: [0, 0, 0], to: [16, 8, 16], faces: ALL_FACES('#all', false) }], textures: { all: 'block/stone' } })],
    ['models/block/stairs.json', json({
      textures: { all: 'block/stone' },
      elements: [
        { from: [0, 0, 0], to: [16, 8, 16], faces: { down: { texture: '#all', cullface: 'down' }, up: { texture: '#all' }, north: { texture: '#all', cullface: 'north' }, south: { texture: '#all', cullface: 'south' }, west: { texture: '#all', cullface: 'west' }, east: { texture: '#all', cullface: 'east' } } },
        { from: [8, 8, 0], to: [16, 16, 16], faces: { up: { texture: '#all', cullface: 'up' }, north: { texture: '#all', cullface: 'north' }, south: { texture: '#all', cullface: 'south' }, west: { texture: '#all' }, east: { texture: '#all', cullface: 'east' } } },
      ],
    })],
    ['models/block/fence_post.json', json({ elements: [{ from: [6, 0, 6], to: [10, 16, 10], faces: ALL_FACES('#all', false) }], textures: { all: 'block/planks' } })],
    ['models/block/fence_side.json', json({ elements: [{ from: [7, 12, 0], to: [9, 15, 9], faces: ALL_FACES('#all', false) }], textures: { all: 'block/planks' } })],
    ['models/block/pane_post.json', json({ elements: [{ from: [7, 0, 7], to: [9, 16, 9], faces: ALL_FACES('#all', false) }], textures: { all: 'block/glass' } })],
    ['models/block/cross.json', json({
      ambientocclusion: false,
      textures: { cross: 'block/poppy' },
      elements: [
        { from: [0.8, 0, 8], to: [15.2, 16, 8], rotation: { origin: [8, 8, 8], axis: 'y', angle: 45, rescale: true }, faces: { north: { texture: '#cross' }, south: { texture: '#cross' } } },
        { from: [8, 0, 0.8], to: [8, 16, 15.2], rotation: { origin: [8, 8, 8], axis: 'y', angle: 45, rescale: true }, faces: { west: { texture: '#cross' }, east: { texture: '#cross' } } },
      ],
    })],
    ['blockstates/stone.json', json({ variants: { '': { model: 'block/stone' } } })],
    ['blockstates/grass_block.json', json({ variants: { 'snowy=false': { model: 'block/grass_block' }, 'snowy=true': { model: 'block/stone' } } })],
    ['blockstates/stone_slab.json', json({ variants: { 'type=bottom': { model: 'block/slab' }, 'type=double': { model: 'block/stone' } } })],
    ['blockstates/stone_stairs.json', json({ variants: {
      'facing=east,half=bottom,shape=straight': { model: 'block/stairs' },
      'facing=south,half=bottom,shape=straight': { model: 'block/stairs', y: 90, uvlock: true },
      'facing=west,half=bottom,shape=straight': { model: 'block/stairs', y: 180, uvlock: true },
      'facing=north,half=bottom,shape=straight': { model: 'block/stairs', y: 270, uvlock: true },
    } })],
    ['blockstates/oak_fence.json', json({ multipart: [
      { apply: { model: 'block/fence_post' } },
      { when: { north: 'true' }, apply: { model: 'block/fence_side', uvlock: true } },
      { when: { OR: [{ east: 'true' }, { west: 'true' }] }, apply: { model: 'block/fence_side', y: 90, uvlock: true } },
    ] })],
    ['blockstates/glass_pane.json', json({ multipart: [{ apply: { model: 'block/pane_post' } }] })],
    ['blockstates/poppy.json', json({ variants: { '': { model: 'block/cross' } } })],
    ['blockstates/water.json', json({ variants: { '': { model: 'block/water' } } })],
    ['models/block/water.json', json({ textures: { particle: 'block/water_still' } })],
  ]);
  return new ResourcePack({ version: '26.1', primary: files, models: null });
}

function sprite(id: string, alpha: number, frames = 1): SpriteImage {
  const pixels = new Uint8ClampedArray(16 * 16 * frames * 4);
  for (let index = 0; index < pixels.length; index += 4) {
    pixels[index] = 200;
    pixels[index + 1] = 200;
    pixels[index + 2] = 200;
    pixels[index + 3] = index % 8 === 0 ? alpha : 255;
  }
  return { id, width: 16, height: 16 * frames, pixels, metadata: frames > 1 ? { animation: { frametime: 2 } } : null };
}

const SPRITES = [
  sprite('modl:missing', 255),
  sprite('modl:unknown', 255),
  sprite('block/stone', 255),
  sprite('block/grass_top', 255),
  sprite('block/planks', 255),
  sprite('block/glass', 0),
  sprite('block/poppy', 0),
  sprite('block/water_still', 180, 4),
  sprite('block/water_flow', 180, 4),
];

function grassColormap(): Colormaps {
  const pixels = new Uint8ClampedArray(256 * 256 * 4);
  for (let index = 0; index < pixels.length; index += 4) {
    pixels[index] = 0x40;
    pixels[index + 1] = 0x80;
    pixels[index + 2] = 0x20;
    pixels[index + 3] = 255;
  }
  return { grass: { width: 256, height: 256, pixels }, foliage: null, dryFoliage: null };
}

function state(text: string): BlockState {
  const bracket = text.indexOf('[');
  const name = (bracket < 0 ? text : text.slice(0, bracket)).replace('minecraft:', '');
  const properties: Record<string, string> = {};
  if (bracket >= 0) {
    for (const pair of text.slice(bracket + 1, -1).split(',')) {
      const [key, value] = pair.split('=');
      if (key !== undefined && value !== undefined) properties[key] = value;
    }
  }
  return { name, properties };
}

function world(blocks: readonly (readonly [number, number, number, string])[]): { service: WorldService; mesh: SectionMesh } {
  const pack = syntheticPack();
  const palette: BlockState[] = [state('air')];
  const states = new Uint32Array(4096);
  for (const [x, y, z, text] of blocks) {
    palette.push(state(text));
    states[blockIndex(x, y, z)] = palette.length - 1;
  }
  const assets = new WorldAssets({ pack, palette, biomeKeys: [], itemKeys: [] }, new BlockLibrary(blockSource(pack)), buildAtlas(SPRITES), grassColormap());
  const service = new WorldService(assets, { key: 'minecraft:overworld', minY: 0, height: 256, hasSkyLight: true }, [{ x: 0, y: 0, z: 0, data: { states, skyLight: null, blockLight: null, biomes: null } }]);
  const key = service.sectionKeys()[0] ?? 0;
  return { service, mesh: service.mesh(key) };
}

function quads(mesh: SectionMesh): number {
  return mesh.solid.quadCount + mesh.cutout.quadCount + mesh.translucent.quadCount;
}

void test('variant keys match exactly and fall back leniently for missing properties', () => {
  const definition = parseBlockStateDefinition({ variants: { 'snowy=false': { model: 'a' }, 'snowy=true': { model: 'b' } } });
  assert.ok(definition !== null);
  assert.equal(definition.parts({ snowy: 'true' })[0]?.[0]?.model, 'b');
  assert.equal(definition.parts({})[0]?.[0]?.model, 'a');
});

void test('multipart conditions support values, alternatives, OR and AND', () => {
  const definition = parseBlockStateDefinition({ multipart: [
    { apply: { model: 'post' } },
    { when: { north: 'true|low' }, apply: { model: 'north' } },
    { when: { OR: [{ east: 'true' }, { west: 'true' }] }, apply: { model: 'side' } },
    { when: { AND: [{ up: 'true' }, { down: 'true' }] }, apply: { model: 'both' } },
  ] });
  const models = (properties: Record<string, string>): string[] => (definition?.parts(properties) ?? []).map((list) => list[0]?.model ?? '');
  assert.deepEqual(models({ north: 'low', east: 'false', west: 'true', up: 'true', down: 'false' }), ['post', 'north', 'side']);
  assert.deepEqual(models({ up: 'true', down: 'true' }), ['post', 'both']);
});

void test('weighted variants are chosen deterministically', () => {
  const variants = [{ model: 'a', x: 0, y: 0, uvlock: false, weight: 1 }, { model: 'b', x: 0, y: 0, uvlock: false, weight: 3 }];
  assert.equal(pickWeighted(variants, 0)?.model, 'a');
  assert.equal(pickWeighted(variants, 1)?.model, 'b');
  assert.equal(pickWeighted(variants, 4)?.model, 'a');
});

void test('state strings keep properties and drop the namespace', () => {
  assert.deepEqual(state('minecraft:oak_stairs[facing=east,half=bottom]'), { name: 'oak_stairs', properties: { facing: 'east', half: 'bottom' } });
});

void test('a lone cube emits six solid faces and neighbours cull shared faces', () => {
  assert.equal(world([[1, 1, 1, 'stone']]).mesh.solid.quadCount, 6);
  assert.equal(world([[1, 1, 1, 'stone'], [2, 1, 1, 'stone']]).mesh.solid.quadCount, 10);
});

void test('slabs do not hide the side of a neighbouring full block', () => {
  const mesh = world([[1, 1, 1, 'stone'], [2, 1, 1, 'stone_slab[type=bottom]']]).mesh;
  assert.equal(mesh.solid.quadCount, 12);
  assert.equal(world([[1, 1, 1, 'stone_slab[type=bottom]'], [1, 2, 1, 'stone']]).mesh.solid.quadCount, 12);
});

void test('stairs rotate with their facing and keep their quads', () => {
  const east = world([[1, 1, 1, 'stone_stairs[facing=east,half=bottom,shape=straight]']]).mesh;
  const north = world([[1, 1, 1, 'stone_stairs[facing=north,half=bottom,shape=straight]']]).mesh;
  assert.equal(east.solid.quadCount, 11);
  assert.equal(north.solid.quadCount, 11);
  const maxZ = (mesh: SectionMesh): number => Math.max(...Array.from(mesh.solid.positions).filter((_, index) => index % 3 === 2));
  const minZOfTop = (mesh: SectionMesh): number => {
    let min = Infinity;
    for (let index = 0; index < mesh.solid.positions.length; index += 3) {
      if ((mesh.solid.positions[index + 1] ?? 0) > 1.99) min = Math.min(min, mesh.solid.positions[index + 2] ?? 0);
    }
    return min;
  };
  assert.equal(maxZ(north), 2);
  assert.equal(minZOfTop(north), 1);
});

void test('fence multipart adds the side arm only when connected', () => {
  assert.equal(world([[1, 1, 1, 'oak_fence[north=false,east=false,south=false,west=false]']]).mesh.solid.quadCount, 6);
  assert.equal(world([[1, 1, 1, 'oak_fence[north=true,east=false,south=false,west=false]']]).mesh.solid.quadCount, 12);
  assert.equal(world([[1, 1, 1, 'oak_fence[north=true,east=true,south=false,west=false]']]).mesh.solid.quadCount, 18);
});

void test('panes and cross plants render in the cutout layer', () => {
  const pane = world([[1, 1, 1, 'glass_pane']]).mesh;
  assert.equal(pane.cutout.quadCount, 6);
  assert.equal(pane.solid.quadCount, 0);
  const poppy = world([[1, 1, 1, 'poppy']]).mesh;
  assert.equal(poppy.cutout.quadCount, 4);
});

void test('water renders translucent top, bottom and open sides with a lowered surface', () => {
  const mesh = world([[1, 1, 1, 'water[level=0]']]).mesh;
  assert.equal(mesh.translucent.quadCount, 11);
  const heights = Array.from(mesh.translucent.positions).filter((_, index) => index % 3 === 1);
  const lone = 1 + ((8 / 9) * 10) / 12;
  assert.ok(Math.abs(Math.max(...heights) - lone) < 0.01);
  const pool: [number, number, number, string][] = [];
  for (let x = 1; x <= 3; x++) for (let z = 1; z <= 3; z++) pool.push([x, 1, z, 'water[level=0]']);
  const center = world(pool).mesh.translucent.positions;
  let centerTop = 0;
  for (let index = 0; index < center.length; index += 3) {
    if ((center[index] ?? 0) === 2.5 || ((center[index] ?? 0) >= 2 && (center[index] ?? 0) <= 3 && (center[index + 2] ?? 0) >= 2 && (center[index + 2] ?? 0) <= 3)) centerTop = Math.max(centerTop, center[index + 1] ?? 0);
  }
  assert.ok(Math.abs(centerTop - (1 + 8 / 9)) < 0.01);
});

void test('tinted faces take the biome colormap colour', () => {
  const mesh = world([[1, 1, 1, 'grass_block[snowy=false]']]).mesh;
  const colors = mesh.solid.colors;
  let found = false;
  for (let vertex = 0; vertex < colors.length / 4; vertex++) {
    if (colors[vertex * 4] === 0x40 && colors[vertex * 4 + 1] === 0x80 && colors[vertex * 4 + 2] === 0x20) found = true;
  }
  assert.ok(found);
});

void test('face shading follows vanilla directions', () => {
  const mesh = world([[1, 5, 1, 'stone']]).mesh;
  const shades = new Set<number>();
  for (let vertex = 0; vertex < mesh.solid.colors.length / 4; vertex++) shades.add(mesh.solid.colors[vertex * 4] ?? 0);
  assert.deepEqual([...shades].sort((left, right) => left - right), [128, 153, 204, 255]);
});

void test('blocks without a block state render as a placeholder cube', () => {
  assert.equal(world([[1, 1, 1, 'modl_unknown']]).mesh.solid.quadCount, 6);
  assert.equal(world([[1, 1, 1, 'not_a_block']]).mesh.solid.quadCount, 6);
});

void test('block changes mark their section dirty and change the mesh', () => {
  const { service } = world([[1, 1, 1, 'stone']]);
  const dirty = service.apply([{ x: 1, y: 1, z: 1, state: 0 }]);
  assert.equal(dirty.size, 1);
  assert.equal(quads(service.mesh([...dirty][0] ?? 0)), 0);
});

void test('section keys round-trip across the whole world border', () => {
  for (const [x, y, z] of [[0, 0, 0], [-1874999, -127, 1874999], [1874999, 127, -1874999], [-1, -4, -1]] as const) {
    assert.deepEqual(unpackSectionKey(sectionKey(x, y, z)), [x, y, z]);
  }
});

void test('legacy derived states find their textures in the atlas', () => {
  const pack = new ResourcePack({ version: '1.8.8', primary: new Map(), models: new Map([
    ['blockstates/grass_block.json', json({ variants: { 'snowy=false': { model: 'block/grass_block' }, 'snowy=true': { model: 'block/grass_block_snow' } } })],
    ['models/block/grass_block.json', json({ elements: [{ from: [0, 0, 0], to: [16, 16, 16], faces: ALL_FACES('#top', true) }], textures: { top: 'block/grass_top' } })],
    ['models/block/grass_block_snow.json', json({ elements: [{ from: [0, 0, 0], to: [16, 16, 16], faces: ALL_FACES('#side', true) }], textures: { side: 'block/grass_block_snow' } })],
  ]) });
  const sprites = collectSprites({ pack, palette: [state('air'), state('grass_block[snowy=false]')], biomeKeys: [], itemKeys: [] }, new BlockLibrary(blockSource(pack)));
  assert.ok(sprites.has('block/grass_block_snow'));
});

void test('coordinates outside the section key range never alias into the world', () => {
  const blocks = new BlockWorld({ hasSkyLight: true });
  assert.equal(blocks.setState(0, 5000, 0, 1), false);
  assert.equal(blocks.setState(2 ** 30, 0, 0, 1), false);
  assert.equal(blocks.sectionKeys().length, 0);
  assert.ok(blockInRange(29_999_999, 2031, -29_999_999));
  assert.ok(!blockInRange(0, 5000, 0));
});
