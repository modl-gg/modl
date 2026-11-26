import { type Direction, DIRECTIONS, DIRECTION_VECTORS, type Vec3, closestDirection } from './Direction.ts';
import { type ModelElement, type ModelFace, type ResolvedModel, resolveTexture } from './ModelResolver.ts';
import { IDENTITY, type Mat3, apply, multiply, rotationX, rotationY, rotationZ, variantRotation } from './Rotation.ts';

export interface BakedQuad {
  readonly positions: Float32Array;
  readonly uvs: Float32Array;
  readonly sprite: string;
  readonly direction: Direction;
  readonly cullface: Direction | null;
  readonly tintindex: number;
  readonly shadeDirection: Direction | null;
  readonly emission: number;
  readonly flush: boolean;
}

export interface BakedModel {
  readonly quads: readonly BakedQuad[];
  readonly ambientOcclusion: boolean;
}

type Corner = readonly [number, number, number];

const FACE_CORNERS: Readonly<Record<Direction, readonly (readonly [0 | 1, 0 | 1, 0 | 1])[]>> = {
  down: [[0, 0, 1], [0, 0, 0], [1, 0, 0], [1, 0, 1]],
  up: [[0, 1, 0], [0, 1, 1], [1, 1, 1], [1, 1, 0]],
  north: [[1, 1, 0], [1, 0, 0], [0, 0, 0], [0, 1, 0]],
  south: [[0, 1, 1], [0, 0, 1], [1, 0, 1], [1, 1, 1]],
  west: [[0, 1, 0], [0, 0, 0], [0, 0, 1], [0, 1, 1]],
  east: [[1, 1, 1], [1, 0, 1], [1, 0, 0], [1, 1, 0]],
};

export function defaultUv(direction: Direction, from: Vec3, to: Vec3): [number, number, number, number] {
  switch (direction) {
    case 'down':
      return [from[0], 16 - to[2], to[0], 16 - from[2]];
    case 'up':
      return [from[0], from[2], to[0], to[2]];
    case 'north':
      return [16 - to[0], 16 - to[1], 16 - from[0], 16 - from[1]];
    case 'south':
      return [from[0], 16 - to[1], to[0], 16 - from[1]];
    case 'west':
      return [from[2], 16 - to[1], to[2], 16 - from[1]];
    case 'east':
      return [16 - to[2], 16 - to[1], 16 - from[2], 16 - from[1]];
  }
}

export function planeUv(direction: Direction, point: Vec3): [number, number] {
  switch (direction) {
    case 'down':
      return [point[0], 16 - point[2]];
    case 'up':
      return [point[0], point[2]];
    case 'north':
      return [16 - point[0], 16 - point[1]];
    case 'south':
      return [point[0], 16 - point[1]];
    case 'west':
      return [point[2], 16 - point[1]];
    case 'east':
      return [16 - point[2], 16 - point[1]];
  }
}

function vertexUv(uv: readonly [number, number, number, number], rotation: number, index: number): [number, number] {
  const shifted = (index + rotation / 90) % 4;
  const u = shifted === 0 || shifted === 1 ? uv[0] : uv[2];
  const v = shifted === 0 || shifted === 3 ? uv[1] : uv[3];
  return [u, v];
}

function elementMatrix(element: ModelElement): { matrix: Mat3; scale: Vec3 } {
  const rotation = element.rotation;
  if (rotation === null) return { matrix: IDENTITY, scale: [1, 1, 1] };
  if (rotation.euler !== null) {
    const matrix = multiply(multiply(rotationX(rotation.euler[0]), rotationY(rotation.euler[1])), rotationZ(rotation.euler[2]));
    return { matrix, scale: [1, 1, 1] };
  }
  const angle = rotation.angle;
  const factor = rotation.rescale && Math.abs(Math.cos(angle * Math.PI / 180)) > 1e-6 ? 1 / Math.cos(angle * Math.PI / 180) : 1;
  switch (rotation.axis) {
    case 'x':
      return { matrix: rotationX(angle), scale: [1, factor, factor] };
    case 'y':
      return { matrix: rotationY(angle), scale: [factor, 1, factor] };
    case 'z':
      return { matrix: rotationZ(angle), scale: [factor, factor, 1] };
    case null:
      return { matrix: IDENTITY, scale: [1, 1, 1] };
  }
}

function transformElementPoint(point: Corner, element: ModelElement, matrix: Mat3, scale: Vec3): [number, number, number] {
  const origin = element.rotation?.origin ?? [8, 8, 8];
  const local: Vec3 = [point[0] - origin[0], point[1] - origin[1], point[2] - origin[2]];
  const rotated = apply(matrix, local);
  return [rotated[0] * scale[0] + origin[0], rotated[1] * scale[1] + origin[1], rotated[2] * scale[2] + origin[2]];
}

function rotateAroundCenter(point: Vec3, matrix: Mat3): [number, number, number] {
  const rotated = apply(matrix, [point[0] - 8, point[1] - 8, point[2] - 8]);
  return [rotated[0] + 8, rotated[1] + 8, rotated[2] + 8];
}

function rotateDirection(direction: Direction, matrix: Mat3): Direction {
  return closestDirection(apply(matrix, DIRECTION_VECTORS[direction]));
}

function solveAffine(source: readonly [number, number][], target: readonly [number, number][]): ((point: readonly [number, number]) => [number, number]) | null {
  const [p0, p1, p2] = source;
  const [q0, q1, q2] = target;
  if (p0 === undefined || p1 === undefined || p2 === undefined || q0 === undefined || q1 === undefined || q2 === undefined) return null;
  const ax = p1[0] - p0[0];
  const ay = p1[1] - p0[1];
  const bx = p2[0] - p0[0];
  const by = p2[1] - p0[1];
  const determinant = ax * by - ay * bx;
  if (Math.abs(determinant) < 1e-9) return null;
  return (point) => {
    const dx = point[0] - p0[0];
    const dy = point[1] - p0[1];
    const s = (dx * by - dy * bx) / determinant;
    const t = (ax * dy - ay * dx) / determinant;
    return [q0[0] + s * (q1[0] - q0[0]) + t * (q2[0] - q0[0]), q0[1] + s * (q1[1] - q0[1]) + t * (q2[1] - q0[1])];
  };
}

function quadDirection(positions: Float32Array, fallback: Direction): Direction {
  const at = (index: number): number => positions[index] ?? 0;
  const ax = at(6) - at(0);
  const ay = at(7) - at(1);
  const az = at(8) - at(2);
  const bx = at(9) - at(3);
  const by = at(10) - at(4);
  const bz = at(11) - at(5);
  const normal: Vec3 = [ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx];
  if (Math.abs(normal[0]) + Math.abs(normal[1]) + Math.abs(normal[2]) < 1e-9) return fallback;
  return closestDirection(normal);
}

function isFlush(direction: Direction, positions: Float32Array): boolean {
  const axis = direction === 'up' || direction === 'down' ? 1 : direction === 'north' || direction === 'south' ? 2 : 0;
  const boundary = direction === 'up' || direction === 'south' || direction === 'east' ? 1 : 0;
  for (let vertex = 0; vertex < 4; vertex++) {
    if (Math.abs((positions[vertex * 3 + axis] ?? 0) - boundary) > 1e-5) return false;
  }
  return true;
}

export interface VariantTransform {
  readonly x: number;
  readonly y: number;
  readonly uvlock: boolean;
}

function bakeFace(model: ResolvedModel, element: ModelElement, direction: Direction, face: ModelFace, transform: VariantTransform): BakedQuad | null {
  const sprite = resolveTexture(model, face.texture);
  if (sprite === null) return null;
  const { matrix, scale } = elementMatrix(element);
  const variant = variantRotation(transform.x, transform.y);
  const rotated = transform.x !== 0 || transform.y !== 0;
  const explicitUv = face.uv ?? defaultUv(direction, element.from, element.to);
  const corners = FACE_CORNERS[direction].map((corner): Corner => [
    corner[0] === 0 ? element.from[0] : element.to[0],
    corner[1] === 0 ? element.from[1] : element.to[1],
    corner[2] === 0 ? element.from[2] : element.to[2],
  ]);
  const explicit = corners.map((_, index) => vertexUv(explicitUv, face.rotation, index));
  const finalDirection = rotateDirection(direction, variant);
  let uvAt = (index: number): [number, number] => explicit[index] ?? [0, 0];
  if (transform.uvlock && rotated) {
    const plane = corners.map((corner) => planeUv(direction, corner));
    const affine = solveAffine(plane, explicit);
    if (affine !== null) {
      uvAt = (index) => {
        const corner = corners[index] ?? [0, 0, 0];
        return affine(planeUv(finalDirection, rotateAroundCenter(corner, variant)));
      };
    }
  }
  const positions = new Float32Array(12);
  const uvs = new Float32Array(8);
  corners.forEach((corner, index) => {
    const local = transformElementPoint(corner, element, matrix, scale);
    const world = rotateAroundCenter(local, variant);
    positions[index * 3] = world[0] / 16;
    positions[index * 3 + 1] = world[1] / 16;
    positions[index * 3 + 2] = world[2] / 16;
    const uv = uvAt(index);
    uvs[index * 2] = uv[0] / 16;
    uvs[index * 2 + 1] = uv[1] / 16;
  });
  const shadeDirection = element.shadeDirection === null ? null : rotateDirection(element.shadeDirection, variant);
  const facing = element.rotation === null ? finalDirection : quadDirection(positions, finalDirection);
  return {
    positions,
    uvs,
    sprite,
    direction: facing,
    cullface: face.cullface === null ? null : rotateDirection(face.cullface, variant),
    tintindex: face.tintindex,
    shadeDirection: element.shade ? shadeDirection : 'up',
    emission: element.lightEmission,
    flush: element.rotation === null && isFlush(facing, positions),
  };
}

export function bakeModel(model: ResolvedModel, transform: VariantTransform): BakedModel {
  const quads: BakedQuad[] = [];
  for (const element of model.elements) {
    for (const direction of DIRECTIONS) {
      const face = element.faces[direction];
      if (face === undefined) continue;
      const quad = bakeFace(model, element, direction, face, transform);
      if (quad !== null) quads.push(quad);
    }
  }
  return { quads, ambientOcclusion: model.ambientOcclusion };
}
