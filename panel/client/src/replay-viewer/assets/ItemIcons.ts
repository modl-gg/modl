import type { BlockLibrary } from '../render/world/BlockLibrary.ts';
import { bareName } from '../format/ids.ts';
import { DIRECTION_VECTORS, FACE_SHADE } from '../render/world/Direction.ts';
import { itemModelId } from '../render/world/ItemModels.ts';
import type { BakedQuad } from '../render/world/ModelBaker.ts';
import { type DisplayTransform, resolveTexture } from '../render/world/ModelResolver.ts';
import { apply, multiply, rotationX, rotationY, rotationZ } from '../render/world/Rotation.ts';
import { scaleColor } from '../render/Lighting.ts';
import { LEATHER_COLOR, tintRule } from '../render/world/Tints.ts';
import type { ResourcePack } from './ResourcePack.ts';
import { specialItemModel, specialItemQuads } from './SpecialItemModels.ts';

export const ICON_SIZE = 64;
const SCALE = ICON_SIZE / 16;
const DEFAULT_GUI: DisplayTransform = { rotation: [30, 225, 0], translation: [0, 0, 0], scale: [0.625, 0.625, 0.625] };
const GRASS_ITEM = 0x7cbd6b;
const FOLIAGE_ITEM = 0x48b518;

export type TextureSource = (textureId: string) => ImageBitmap | null;

interface Projected {
  readonly points: readonly (readonly [number, number])[];
  readonly depth: number;
  readonly quad: BakedQuad;
}

function canvas2d(width: number, height: number): [OffscreenCanvas, OffscreenCanvasRenderingContext2D] | null {
  const canvas = new OffscreenCanvas(width, height);
  const context = canvas.getContext('2d');
  if (context === null) return null;
  context.imageSmoothingEnabled = false;
  return [canvas, context];
}

function frameOf(bitmap: ImageBitmap): { width: number; height: number } {
  const size = Math.min(bitmap.width, bitmap.height);
  return { width: size, height: size };
}

function tintFor(item: string, tintIndex: number): number {
  if (tintIndex < 0) return 0xffffff;
  const name = bareName(item);
  if (name.startsWith('leather_') && tintIndex === 0) return LEATHER_COLOR;
  const rule = tintRule({ name, properties: {} });
  switch (rule.kind) {
    case 'grass':
      return GRASS_ITEM;
    case 'foliage':
    case 'dry_foliage':
      return FOLIAGE_ITEM;
    case 'constant':
    case 'redstone':
    case 'stem':
      return rule.color;
    case 'water':
    case 'none':
      return 0xffffff;
  }
}

function shadedSprite(bitmap: ImageBitmap, color: number): OffscreenCanvas | null {
  const { width, height } = frameOf(bitmap);
  const made = canvas2d(width, height);
  if (made === null) return null;
  const [canvas, context] = made;
  context.drawImage(bitmap, 0, 0, width, height, 0, 0, width, height);
  if ((color & 0xffffff) !== 0xffffff) {
    context.globalCompositeOperation = 'multiply';
    context.fillStyle = `#${(color & 0xffffff).toString(16).padStart(6, '0')}`;
    context.fillRect(0, 0, width, height);
    context.globalCompositeOperation = 'destination-in';
    context.drawImage(bitmap, 0, 0, width, height, 0, 0, width, height);
  }
  return canvas;
}

export class ItemIcons {
  private readonly pack: ResourcePack;
  private readonly library: BlockLibrary;
  private readonly textures: TextureSource;
  private readonly cache = new Map<string, ImageBitmap | null>();

  constructor(pack: ResourcePack, library: BlockLibrary, textures: TextureSource) {
    this.pack = pack;
    this.library = library;
    this.textures = textures;
  }

  dispose(): void {
    for (const icon of this.cache.values()) icon?.close();
    this.cache.clear();
  }

  icon(itemKey: string): ImageBitmap | null {
    const key = bareName(itemKey);
    if (this.cache.has(key)) return this.cache.get(key) ?? null;
    const icon = this.render(key);
    this.cache.set(key, icon);
    return icon;
  }

  private render(item: string): ImageBitmap | null {
    const modelId = itemModelId(this.pack, item);
    if (modelId === null) return null;
    const model = this.library.resolveModel(modelId);
    if (model === null) return null;
    const made = canvas2d(ICON_SIZE, ICON_SIZE);
    if (made === null) return null;
    const [canvas, context] = made;
    if (model.generatedItem) {
      let drew = false;
      for (let layer = 0; layer < 8; layer++) {
        const sprite = resolveTexture(model, `#layer${String(layer)}`);
        if (sprite === null) continue;
        const bitmap = this.textures(sprite);
        if (bitmap === null) continue;
        const shaded = shadedSprite(bitmap, tintFor(item, layer));
        if (shaded === null) continue;
        context.drawImage(shaded, 0, 0, shaded.width, shaded.height, 0, 0, ICON_SIZE, ICON_SIZE);
        drew = true;
      }
      return drew ? canvas.transferToImageBitmap() : null;
    }
    const baked = this.library.bakeModelId(modelId);
    const quads = baked === null || baked.quads.length === 0 ? this.specialQuads(item) : baked.quads;
    if (quads.length === 0) return null;
    this.drawModel(context, quads, model.display['gui'] ?? DEFAULT_GUI, item);
    return canvas.transferToImageBitmap();
  }

  private specialQuads(item: string): readonly BakedQuad[] {
    const special = specialItemModel(item);
    if (special === null) return [];
    const texture = special.textures.find((candidate) => this.textures(candidate) !== null);
    return texture === undefined ? [] : specialItemQuads(special, texture);
  }

  private drawModel(context: OffscreenCanvasRenderingContext2D, quads: readonly BakedQuad[], display: DisplayTransform, item: string): void {
    const rotation = multiply(multiply(rotationX(display.rotation[0]), rotationY(display.rotation[1])), rotationZ(display.rotation[2]));
    const projected: Projected[] = [];
    for (const quad of quads) {
      const points: [number, number][] = [];
      let depth = 0;
      for (let vertex = 0; vertex < 4; vertex++) {
        const local: [number, number, number] = [
          ((quad.positions[vertex * 3] ?? 0) - 0.5) * 16 * display.scale[0],
          ((quad.positions[vertex * 3 + 1] ?? 0) - 0.5) * 16 * display.scale[1],
          ((quad.positions[vertex * 3 + 2] ?? 0) - 0.5) * 16 * display.scale[2],
        ];
        const turned = apply(rotation, local);
        const world: [number, number, number] = [turned[0] + display.translation[0], turned[1] + display.translation[1], turned[2] + display.translation[2]];
        points.push([(8 + world[0]) * SCALE, (8 - world[1]) * SCALE]);
        depth += world[2];
      }
      if (apply(rotation, DIRECTION_VECTORS[quad.direction])[2] <= 0) continue;
      projected.push({ points, depth: depth / 4, quad });
    }
    projected.sort((left, right) => left.depth - right.depth);
    for (const face of projected) this.drawFace(context, face, item);
  }

  private drawFace(context: OffscreenCanvasRenderingContext2D, face: Projected, item: string): void {
    const bitmap = this.textures(face.quad.sprite);
    if (bitmap === null) return;
    const shade = face.quad.shadeDirection === 'up' ? 1 : FACE_SHADE[face.quad.direction];
    const sprite = shadedSprite(bitmap, scaleColor(tintFor(item, face.quad.tintindex), shade));
    if (sprite === null) return;
    const uv = (index: number): [number, number] => [(face.quad.uvs[index * 2] ?? 0) * sprite.width, (face.quad.uvs[index * 2 + 1] ?? 0) * sprite.height];
    const [u0, v0] = uv(0);
    const [u1, v1] = uv(1);
    const [u2, v2] = uv(2);
    const [p0, p1, p2] = face.points;
    if (p0 === undefined || p1 === undefined || p2 === undefined) return;
    const du1 = u1 - u0;
    const dv1 = v1 - v0;
    const du2 = u2 - u0;
    const dv2 = v2 - v0;
    const determinant = du1 * dv2 - du2 * dv1;
    if (Math.abs(determinant) < 1e-9) return;
    const dx1 = p1[0] - p0[0];
    const dy1 = p1[1] - p0[1];
    const dx2 = p2[0] - p0[0];
    const dy2 = p2[1] - p0[1];
    const a = (dx1 * dv2 - dx2 * dv1) / determinant;
    const c = (dx2 * du1 - dx1 * du2) / determinant;
    const b = (dy1 * dv2 - dy2 * dv1) / determinant;
    const d = (dy2 * du1 - dy1 * du2) / determinant;
    const e = p0[0] - a * u0 - c * v0;
    const f = p0[1] - b * u0 - d * v0;
    context.save();
    context.beginPath();
    face.points.forEach((point, index) => {
      if (index === 0) context.moveTo(point[0], point[1]);
      else context.lineTo(point[0], point[1]);
    });
    context.closePath();
    context.clip();
    context.setTransform(a, b, c, d, e, f);
    context.drawImage(sprite, 0, 0);
    context.restore();
  }
}
