import { decodeRgba } from '../../assets/decodeImage.ts';
import { ResourcePack } from '../../assets/ResourcePack.ts';
import { type SpriteImage, buildAtlas } from './Atlas.ts';
import { BlockLibrary, MISSING_SPRITE, UNKNOWN_SPRITE } from './BlockLibrary.ts';
import { DEFAULT_DIMENSION } from '../../format/ReplayModel.ts';
import { sectionKey, unpackSectionKey } from '../../format/WorldLayout.ts';
import { SECTION_BIT_BYTES, packCollision } from './CollisionBits.ts';
import { FullCubes } from './FullCubes.ts';
import type { Colormap, Colormaps } from './Tints.ts';
import { type InitMessage, type WireWorld, type WorldMessage, type WorldRequest, type WorldResponse, meshTransfers, parseWorldRequest } from './worldProtocol.ts';
import { WorldAssets, WorldService, blockSource, collectSprites } from './WorldService.ts';

const SLICE_MS = 12;

let assets: WorldAssets | null = null;
let fullCubes: FullCubes | null = null;
let service: WorldService | null = null;
const unopened = new Map<number, WireWorld>();
const services = new Map<number, WorldService>();
let generation = -1;
let focus: readonly [number, number, number] = [0, 0, 0];
let order: number[] = [];
let orderIndex = 0;
const pending = new Set<number>();
const dirty = new Set<number>();
let scheduled = false;
let focusChanged = false;
let queue: Promise<void> = Promise.resolve();

function send(message: WorldResponse, transfer: Transferable[] = []): void {
  postMessage(message, { transfer });
}

function generatedSprite(id: string, color: (x: number, y: number) => readonly [number, number, number]): SpriteImage {
  const pixels = new Uint8ClampedArray(16 * 16 * 4);
  for (let y = 0; y < 16; y++) {
    for (let x = 0; x < 16; x++) {
      const [red, green, blue] = color(x, y);
      const offset = (y * 16 + x) * 4;
      pixels[offset] = red;
      pixels[offset + 1] = green;
      pixels[offset + 2] = blue;
      pixels[offset + 3] = 255;
    }
  }
  return { id, width: 16, height: 16, pixels, metadata: null };
}

const MISSING = generatedSprite(MISSING_SPRITE, (x, y) => ((x < 8) !== (y < 8) ? [248, 0, 248] : [0, 0, 0]));
const UNKNOWN = generatedSprite(UNKNOWN_SPRITE, (x, y) => (x === 0 || y === 0 || x === 15 || y === 15 ? [112, 112, 112] : [150, 150, 150]));

async function colormap(pack: ResourcePack, id: string): Promise<Colormap | null> {
  const bytes = pack.texture(id);
  if (bytes === null) return null;
  const image = await decodeRgba(bytes);
  return image === null ? null : { width: image.width, height: image.height, pixels: image.pixels };
}

async function initialize(message: InitMessage): Promise<void> {
  const pack = new ResourcePack({ version: message.version, primary: message.primary, models: message.models });
  const library = new BlockLibrary(blockSource(pack));
  const setup = { pack, palette: message.palette, biomeKeys: message.biomeKeys, itemKeys: message.itemKeys };
  const images: SpriteImage[] = [MISSING, UNKNOWN];
  const ids = [...collectSprites(setup, library)].filter((id) => id !== MISSING_SPRITE && id !== UNKNOWN_SPRITE);
  const decoded = await Promise.all(ids.map(async (id): Promise<SpriteImage | null> => {
    const bytes = pack.texture(id);
    const image = bytes === null ? null : await decodeRgba(bytes);
    return image === null ? null : { id, width: image.width, height: image.height, pixels: image.pixels, metadata: pack.textureMetadata(id) };
  }));
  for (const image of decoded) if (image !== null) images.push(image);
  images.sort((left, right) => (left.id < right.id ? -1 : left.id > right.id ? 1 : 0));
  const atlas = buildAtlas(images);
  const colormaps: Colormaps = {
    grass: await colormap(pack, 'colormap/grass'),
    foliage: await colormap(pack, 'colormap/foliage'),
    dryFoliage: await colormap(pack, 'colormap/dry_foliage'),
  };
  assets = new WorldAssets(setup, library, { size: atlas.size, sprites: atlas.sprites }, colormaps);
  fullCubes = new FullCubes(library, message.palette);
  for (const world of message.worlds) unopened.set(world.world, world);
  send({ type: 'atlas', size: atlas.size, levels: atlas.levels, sprites: [...atlas.sprites.values()] }, atlas.levels.map((level) => level.buffer));
}

function sendCollision(world: WireWorld, cubes: FullCubes): void {
  const fullCube = (state: number): boolean => cubes.has(state);
  const solid = world.sections.filter((section) => section.states !== null);
  const keys = new Float64Array(solid.length);
  const bits = new Uint8Array(solid.length * SECTION_BIT_BYTES);
  let count = 0;
  for (const section of solid) {
    if (section.states === null || !packCollision(section.states, fullCube, bits, count * SECTION_BIT_BYTES)) continue;
    keys[count] = sectionKey(section.x, section.y, section.z);
    count += 1;
  }
  const packedKeys = keys.slice(0, count);
  const packedBits = bits.slice(0, count * SECTION_BIT_BYTES);
  send({ type: 'collision', world: world.world, keys: packedKeys, bits: packedBits }, [packedKeys.buffer, packedBits.buffer]);
}

function distance(key: number): number {
  const [x, y, z] = unpackSectionKey(key);
  const dx = x * 16 + 8 - focus[0];
  const dy = y * 16 + 8 - focus[1];
  const dz = z * 16 + 8 - focus[2];
  return dx * dx + dy * dy + dz * dz;
}

function reorder(): void {
  const keyed = [...pending].map((key) => ({ key, distance: distance(key) }));
  keyed.sort((left, right) => left.distance - right.distance);
  order = keyed.map((entry) => entry.key);
  orderIndex = 0;
  focusChanged = false;
}

function nextKey(): number | null {
  for (const key of dirty) {
    dirty.delete(key);
    return key;
  }
  while (orderIndex < order.length) {
    const key = order[orderIndex];
    orderIndex += 1;
    if (key !== undefined && pending.delete(key)) return key;
  }
  return null;
}

function work(): void {
  scheduled = false;
  if (service === null) return;
  if (focusChanged) reorder();
  const started = performance.now();
  while (performance.now() - started < SLICE_MS) {
    const key = nextKey();
    if (key === null) {
      send({ type: 'idle', generation });
      return;
    }
    const [x, y, z] = unpackSectionKey(key);
    try {
      const mesh = service.mesh(key);
      send({ type: 'section', generation, key, x, y, z, mesh }, meshTransfers(mesh));
    } catch (error) {
      send({ type: 'error', message: error instanceof Error ? error.message : 'section mesh failed' });
    }
  }
  schedule();
}

function schedule(): void {
  if (scheduled) return;
  scheduled = true;
  setTimeout(work, 0);
}

function worldService(loaded: WorldAssets, cubes: FullCubes, index: number): WorldService {
  const existing = services.get(index);
  if (existing !== undefined) return existing;
  const world = unopened.get(index) ?? { world: index, dimension: DEFAULT_DIMENSION, sections: [] };
  unopened.delete(index);
  sendCollision(world, cubes);
  const created = new WorldService(loaded, world.dimension, world.sections.map((section) => ({ x: section.x, y: section.y, z: section.z, data: section })));
  services.set(index, created);
  return created;
}

function openWorld(message: WorldMessage): void {
  if (assets === null || fullCubes === null) return;
  generation = message.generation;
  focus = message.focus;
  service = worldService(assets, fullCubes, message.world);
  pending.clear();
  dirty.clear();
  service.apply(message.changes);
  for (const key of service.sectionKeys()) pending.add(key);
  reorder();
  schedule();
}

async function handle(request: WorldRequest): Promise<void> {
  switch (request.type) {
    case 'init':
      await initialize(request);
      return;
    case 'world':
      openWorld(request);
      return;
    case 'changes':
      if (service === null || request.generation !== generation) return;
      for (const key of service.apply(request.changes)) {
        pending.delete(key);
        dirty.add(key);
      }
      schedule();
      return;
    case 'focus':
      focus = request.focus;
      focusChanged = true;
      return;
  }
}

addEventListener('message', (event: MessageEvent<unknown>) => {
  const request = parseWorldRequest(event.data);
  if (request === null) return;
  queue = queue.then(async () => handle(request)).catch((error: unknown) => {
    send({ type: 'error', message: error instanceof Error ? error.message : 'world worker failed' });
  });
});
