import { BoxGeometry, type BufferGeometry, Float32BufferAttribute, Group, type Material, Mesh, Object3D } from 'three';

export type Triple = readonly [number, number, number];

export interface CubeDef {
  readonly uv: readonly [number, number];
  readonly from: Triple;
  readonly size: Triple;
  readonly inflate?: number;
  readonly mirror?: boolean;
}

export interface PartDef {
  readonly name: string;
  readonly pivot: Triple;
  readonly rotation?: Triple;
  readonly cubes: readonly CubeDef[];
  readonly children?: readonly PartDef[];
}

export interface ModelDef {
  readonly parts: readonly PartDef[];
}

export function cube(u: number, v: number, from: Triple, size: Triple, inflate = 0, mirror = false): CubeDef {
  return { uv: [u, v], from, size, inflate, mirror };
}

export function part(name: string, pivot: Triple, cubes: readonly CubeDef[], rotation?: Triple, children?: readonly PartDef[]): PartDef {
  return { name, pivot, cubes, ...(rotation === undefined ? {} : { rotation }), ...(children === undefined ? {} : { children }) };
}

type Corner = readonly [number, number];

function faceUv(x1: number, y1: number, x2: number, y2: number, width: number, height: number): Corner[] {
  return [
    [x1 / width, y2 / height],
    [x2 / width, y2 / height],
    [x2 / width, y1 / height],
    [x1 / width, y1 / height],
  ];
}

export function boxGeometry(definition: CubeDef, textureWidth: number, textureHeight: number): BufferGeometry {
  const inflate = definition.inflate ?? 0;
  const [width, height, depth] = definition.size;
  const geometry = new BoxGeometry(width + inflate * 2, height + inflate * 2, depth + inflate * 2);
  const [u, v] = definition.uv;
  const top = faceUv(u + depth, v, u + width + depth, v + depth, textureWidth, textureHeight);
  const bottom = faceUv(u + width + depth, v, u + width * 2 + depth, v + depth, textureWidth, textureHeight);
  const right = faceUv(u, v + depth, u + depth, v + depth + height, textureWidth, textureHeight);
  const front = faceUv(u + depth, v + depth, u + width + depth, v + depth + height, textureWidth, textureHeight);
  const left = faceUv(u + width + depth, v + depth, u + width + depth * 2, v + height + depth, textureWidth, textureHeight);
  const back = faceUv(u + width + depth * 2, v + depth, u + width * 2 + depth * 2, v + height + depth, textureWidth, textureHeight);
  const mirror = definition.mirror === true;
  const plusX = mirror ? mirrored(right) : left;
  const minusX = mirror ? mirrored(left) : right;
  const order: Corner[] = [
    ...pick(plusX, [3, 2, 0, 1]),
    ...pick(minusX, [3, 2, 0, 1]),
    ...pick(mirror ? mirrored(top) : top, [3, 2, 0, 1]),
    ...pick(mirror ? mirrored(bottom) : bottom, [0, 1, 3, 2]),
    ...pick(mirror ? mirrored(front) : front, [3, 2, 0, 1]),
    ...pick(mirror ? mirrored(back) : back, [3, 2, 0, 1]),
  ];
  geometry.setAttribute('uv', new Float32BufferAttribute(order.flatMap((corner) => [corner[0], corner[1]]), 2));
  geometry.translate(definition.from[0] + width / 2, -(definition.from[1] + height / 2), -(definition.from[2] + depth / 2));
  return geometry;
}

function mirrored(corners: Corner[]): Corner[] {
  const [a, b, c, d] = corners;
  if (a === undefined || b === undefined || c === undefined || d === undefined) return corners;
  return [b, a, d, c];
}

function pick(corners: Corner[], order: readonly number[]): Corner[] {
  return order.map((index) => corners[index] ?? [0, 0]);
}

export interface BuiltModel {
  readonly root: Group;
  readonly parts: ReadonlyMap<string, Object3D>;
  readonly rest: ReadonlyMap<string, { readonly position: Triple; readonly rotation: Triple }>;
}

function buildPart(definition: PartDef, material: Material, textureWidth: number, textureHeight: number, top: boolean, parts: Map<string, Object3D>, rest: Map<string, { position: Triple; rotation: Triple }>): Object3D {
  const node = new Object3D();
  node.name = definition.name;
  const position: Triple = [definition.pivot[0], top ? 24 - definition.pivot[1] : -definition.pivot[1], -definition.pivot[2]];
  node.position.set(position[0], position[1], position[2]);
  const rotation = definition.rotation ?? [0, 0, 0];
  const converted: Triple = [rotation[0], -rotation[1], -rotation[2]];
  node.rotation.set(converted[0], converted[1], converted[2], 'ZYX');
  for (const cubeDefinition of definition.cubes) {
    node.add(new Mesh(boxGeometry(cubeDefinition, textureWidth, textureHeight), material));
  }
  for (const child of definition.children ?? []) node.add(buildPart(child, material, textureWidth, textureHeight, false, parts, rest));
  parts.set(definition.name, node);
  rest.set(definition.name, { position, rotation: converted });
  return node;
}

export function buildModel(definition: ModelDef, material: Material, textureWidth: number, textureHeight: number): BuiltModel {
  const root = new Group();
  const parts = new Map<string, Object3D>();
  const rest = new Map<string, { position: Triple; rotation: Triple }>();
  for (const definitionPart of definition.parts) root.add(buildPart(definitionPart, material, textureWidth, textureHeight, true, parts, rest));
  root.scale.setScalar(1 / 16);
  return { root, parts, rest };
}

export function resetPose(model: BuiltModel): void {
  for (const [name, node] of model.parts) {
    const rest = model.rest.get(name);
    if (rest === undefined) continue;
    node.position.set(rest.position[0], rest.position[1], rest.position[2]);
    node.rotation.set(rest.rotation[0], rest.rotation[1], rest.rotation[2], 'ZYX');
  }
}

export function javaRotate(node: Object3D | undefined, x: number, y: number, z: number): void {
  if (node === undefined) return;
  node.rotation.set(x, -y, -z, 'ZYX');
}
