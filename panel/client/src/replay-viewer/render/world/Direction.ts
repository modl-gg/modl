export type Direction = 'down' | 'up' | 'north' | 'south' | 'west' | 'east';

export const DIRECTIONS: readonly Direction[] = ['down', 'up', 'north', 'south', 'west', 'east'];

export type Vec3 = readonly [number, number, number];

export const DIRECTION_VECTORS: Readonly<Record<Direction, Vec3>> = {
  down: [0, -1, 0],
  up: [0, 1, 0],
  north: [0, 0, -1],
  south: [0, 0, 1],
  west: [-1, 0, 0],
  east: [1, 0, 0],
};

export const OPPOSITE: Readonly<Record<Direction, Direction>> = {
  down: 'up',
  up: 'down',
  north: 'south',
  south: 'north',
  west: 'east',
  east: 'west',
};

export const DIRECTION_INDEX: Readonly<Record<Direction, number>> = {
  down: 0,
  up: 1,
  north: 2,
  south: 3,
  west: 4,
  east: 5,
};

export const FACE_SHADE: Readonly<Record<Direction, number>> = {
  down: 0.5,
  up: 1,
  north: 0.8,
  south: 0.8,
  west: 0.6,
  east: 0.6,
};

export const PLANE_AXES: Readonly<Record<Direction, readonly [number, number]>> = {
  down: [0, 2],
  up: [0, 2],
  north: [0, 1],
  south: [0, 1],
  west: [2, 1],
  east: [2, 1],
};

export function isDirection(value: unknown): value is Direction {
  return value === 'down' || value === 'up' || value === 'north' || value === 'south' || value === 'west' || value === 'east';
}

export function closestDirection(vector: Vec3): Direction {
  let best: Direction = 'up';
  let bestDot = -Infinity;
  for (const direction of DIRECTIONS) {
    const axis = DIRECTION_VECTORS[direction];
    const dot = axis[0] * vector[0] + axis[1] * vector[1] + axis[2] * vector[2];
    if (dot > bestDot) {
      bestDot = dot;
      best = direction;
    }
  }
  return best;
}
