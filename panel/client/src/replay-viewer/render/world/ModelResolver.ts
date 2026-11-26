import { bareName } from '../../format/ids.ts';
import {
  type JsonRecord,
  arrayField,
  booleanField,
  isRecord,
  numberField,
  numberTuple,
  recordField,
  stringField,
} from '../../assets/json.ts';
import { type Direction, DIRECTIONS, type Vec3, isDirection } from './Direction.ts';

export type Axis = 'x' | 'y' | 'z';

export interface ElementRotation {
  readonly origin: Vec3;
  readonly axis: Axis | null;
  readonly angle: number;
  readonly rescale: boolean;
  readonly euler: Vec3 | null;
}

export interface ModelFace {
  readonly texture: string;
  readonly uv: readonly [number, number, number, number] | null;
  readonly cullface: Direction | null;
  readonly rotation: number;
  readonly tintindex: number;
}

export interface ModelElement {
  readonly from: Vec3;
  readonly to: Vec3;
  readonly rotation: ElementRotation | null;
  readonly shade: boolean;
  readonly shadeDirection: Direction | null;
  readonly lightEmission: number;
  readonly faces: Partial<Record<Direction, ModelFace>>;
}

export interface DisplayTransform {
  readonly rotation: Vec3;
  readonly translation: Vec3;
  readonly scale: Vec3;
}

export interface ResolvedModel {
  readonly id: string;
  readonly elements: readonly ModelElement[];
  readonly textures: Readonly<Record<string, string>>;
  readonly ambientOcclusion: boolean;
  readonly generatedItem: boolean;
  readonly display: Readonly<Record<string, DisplayTransform>>;
}

export type ModelLookup = (id: string) => unknown;

const MAX_PARENT_DEPTH = 32;
const MAX_TEXTURE_DEPTH = 16;
const GENERATED_PARENTS = new Set(['builtin/generated']);

export function normalizeModelId(id: string): string {
  const bare = bareName(id);
  return bare.includes('/') ? bare : `block/${bare}`;
}

function vec3(value: unknown, fallback: Vec3): Vec3 {
  const tuple = numberTuple(value, 3);
  return tuple === null ? fallback : [tuple[0] ?? 0, tuple[1] ?? 0, tuple[2] ?? 0];
}

function parseRotation(value: unknown): ElementRotation | null {
  if (!isRecord(value)) return null;
  const origin = vec3(value['origin'], [8, 8, 8]);
  const axis = stringField(value, 'axis');
  const angle = numberField(value, 'angle') ?? 0;
  const rescale = booleanField(value, 'rescale') ?? false;
  if (axis === 'x' || axis === 'y' || axis === 'z') return { origin, axis, angle, rescale, euler: null };
  const x = numberField(value, 'x');
  const y = numberField(value, 'y');
  const z = numberField(value, 'z');
  if (x === null && y === null && z === null) return null;
  return { origin, axis: null, angle: 0, rescale, euler: [x ?? 0, y ?? 0, z ?? 0] };
}

function parseFace(value: unknown): ModelFace | null {
  if (!isRecord(value)) return null;
  const texture = stringField(value, 'texture');
  if (texture === null) return null;
  const uv = numberTuple(value['uv'], 4);
  const cull = value['cullface'] ?? value['cull'];
  return {
    texture,
    uv: uv === null ? null : [uv[0] ?? 0, uv[1] ?? 0, uv[2] ?? 0, uv[3] ?? 0],
    cullface: isDirection(cull) ? cull : null,
    rotation: ((numberField(value, 'rotation') ?? 0) % 360 + 360) % 360,
    tintindex: numberField(value, 'tintindex') ?? -1,
  };
}

function parseElement(value: unknown): ModelElement | null {
  if (!isRecord(value)) return null;
  const facesRecord = recordField(value, 'faces');
  if (facesRecord === null) return null;
  const faces: Partial<Record<Direction, ModelFace>> = {};
  for (const direction of DIRECTIONS) {
    const face = parseFace(facesRecord[direction]);
    if (face !== null) faces[direction] = face;
  }
  const override = value['shade_direction_override'];
  return {
    from: vec3(value['from'], [0, 0, 0]),
    to: vec3(value['to'], [16, 16, 16]),
    rotation: parseRotation(value['rotation']),
    shade: booleanField(value, 'shade') ?? true,
    shadeDirection: isDirection(override) ? override : null,
    lightEmission: numberField(value, 'light_emission') ?? 0,
    faces,
  };
}

function parseDisplay(value: JsonRecord | null): Record<string, DisplayTransform> {
  const display: Record<string, DisplayTransform> = {};
  if (value === null) return display;
  for (const [key, entry] of Object.entries(value)) {
    if (!isRecord(entry)) continue;
    display[key] = {
      rotation: vec3(entry['rotation'], [0, 0, 0]),
      translation: vec3(entry['translation'], [0, 0, 0]),
      scale: vec3(entry['scale'], [1, 1, 1]),
    };
  }
  return display;
}

export class ModelResolver {
  private readonly lookup: ModelLookup;
  private readonly cache = new Map<string, ResolvedModel | null>();

  constructor(lookup: ModelLookup) {
    this.lookup = lookup;
  }

  resolve(rawId: string): ResolvedModel | null {
    const id = normalizeModelId(rawId);
    const cached = this.cache.get(id);
    if (cached !== undefined) return cached;
    const resolved = this.build(id);
    this.cache.set(id, resolved);
    return resolved;
  }

  private build(id: string): ResolvedModel | null {
    const chain: JsonRecord[] = [];
    let generatedItem = false;
    let current: string | null = id;
    for (let depth = 0; current !== null && depth < MAX_PARENT_DEPTH; depth++) {
      if (GENERATED_PARENTS.has(current)) {
        generatedItem = true;
        break;
      }
      const json = this.lookup(current);
      if (!isRecord(json)) {
        if (depth === 0) return null;
        break;
      }
      chain.push(json);
      const parent = stringField(json, 'parent');
      current = parent === null ? null : normalizeModelId(parent);
    }
    const textures: Record<string, string> = {};
    let elements: ModelElement[] | null = null;
    let ambientOcclusion: boolean | null = null;
    let display: Record<string, DisplayTransform> = {};
    for (let index = chain.length - 1; index >= 0; index--) {
      const json = chain[index];
      if (json === undefined) continue;
      const texturesRecord = recordField(json, 'textures');
      if (texturesRecord !== null) {
        for (const [key, value] of Object.entries(texturesRecord)) {
          if (typeof value === 'string') textures[key] = value;
          else if (isRecord(value) && typeof value['sprite'] === 'string') textures[key] = value['sprite'];
        }
      }
      if (Array.isArray(json['elements'])) {
        elements = arrayField(json, 'elements').map(parseElement).filter((element) => element !== null);
      }
      const ao = booleanField(json, 'ambientocclusion');
      if (ao !== null) ambientOcclusion = ao;
      const displayRecord = recordField(json, 'display');
      if (displayRecord !== null) display = { ...display, ...parseDisplay(displayRecord) };
    }
    return {
      id,
      elements: elements ?? [],
      textures,
      ambientOcclusion: ambientOcclusion ?? true,
      generatedItem,
      display,
    };
  }
}

export function resolveTexture(model: ResolvedModel, reference: string): string | null {
  let current = reference;
  for (let depth = 0; depth < MAX_TEXTURE_DEPTH; depth++) {
    if (!current.startsWith('#')) return bareName(current);
    const next = model.textures[current.slice(1)];
    if (next === undefined) return null;
    current = next;
  }
  return null;
}
