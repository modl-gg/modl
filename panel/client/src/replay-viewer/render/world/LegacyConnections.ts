import type { BlockProperties, BlockState } from '../../format/BlockState.ts';

export type NeighborLookup = (dx: number, dy: number, dz: number) => BlockState | null;

export type SolidTest = (state: BlockState) => boolean;

type Horizontal = 'north' | 'south' | 'east' | 'west';

const HORIZONTAL: readonly (readonly [Horizontal, number, number])[] = [
  ['north', 0, -1],
  ['south', 0, 1],
  ['west', -1, 0],
  ['east', 1, 0],
];

const OFFSETS: Readonly<Record<Horizontal, readonly [number, number]>> = { north: [0, -1], south: [0, 1], west: [-1, 0], east: [1, 0] };
const COUNTER_CLOCKWISE: Readonly<Record<Horizontal, Horizontal>> = { north: 'west', west: 'south', south: 'east', east: 'north' };
const OPPOSITE: Readonly<Record<Horizontal, Horizontal>> = { north: 'south', south: 'north', west: 'east', east: 'west' };

const REDSTONE_COMPONENTS = /^(repeater|comparator|redstone_torch|redstone_wall_torch|lever|redstone_block|observer|daylight_detector|tripwire_hook|target|lectern|trapped_chest|.*_button|.*_pressure_plate)$/;
const SNOW_COVERS = new Set(['snow', 'snow_block', 'powder_snow']);
const SNOWY_BLOCKS = new Set(['grass_block', 'podzol', 'mycelium']);

function horizontal(value: string | undefined): Horizontal | null {
  return value === 'north' || value === 'south' || value === 'east' || value === 'west' ? value : null;
}

function isStairs(state: BlockState | null): state is BlockState {
  return state?.name.endsWith('_stairs') === true;
}

function isFence(name: string): boolean {
  return name.endsWith('_fence');
}

function isPane(name: string): boolean {
  return name.endsWith('_pane') || name === 'iron_bars';
}

function isWall(name: string): boolean {
  return name.endsWith('_wall');
}

function isGlass(name: string): boolean {
  return name === 'glass' || name.endsWith('_stained_glass') || name === 'tinted_glass';
}

function withProperties(state: BlockState, changes: BlockProperties): BlockState | null {
  let changed = false;
  for (const [key, value] of Object.entries(changes)) {
    if (state.properties[key] !== value) changed = true;
  }
  return changed ? { name: state.name, properties: { ...state.properties, ...changes } } : null;
}

function stairsShape(state: BlockState, neighbor: NeighborLookup): string {
  const facing = horizontal(state.properties['facing']);
  if (facing === null) return 'straight';
  const half = state.properties['half'];
  const canTake = (direction: Horizontal): boolean => {
    const [dx, dz] = OFFSETS[direction];
    const other = neighbor(dx, 0, dz);
    return !isStairs(other) || other.properties['facing'] !== facing || other.properties['half'] !== half;
  };
  const [fx, fz] = OFFSETS[facing];
  const front = neighbor(fx, 0, fz);
  if (isStairs(front) && front.properties['half'] === half) {
    const frontFacing = horizontal(front.properties['facing']);
    if (frontFacing !== null && frontFacing !== facing && frontFacing !== OPPOSITE[facing] && canTake(OPPOSITE[frontFacing])) {
      return frontFacing === COUNTER_CLOCKWISE[facing] ? 'outer_left' : 'outer_right';
    }
  }
  const back = neighbor(-fx, 0, -fz);
  if (isStairs(back) && back.properties['half'] === half) {
    const backFacing = horizontal(back.properties['facing']);
    if (backFacing !== null && backFacing !== facing && backFacing !== OPPOSITE[facing] && canTake(backFacing)) {
      return backFacing === COUNTER_CLOCKWISE[facing] ? 'inner_left' : 'inner_right';
    }
  }
  return 'straight';
}

function connections(test: (other: BlockState, direction: Horizontal) => boolean, neighbor: NeighborLookup): Record<Horizontal, boolean> {
  const result: Record<Horizontal, boolean> = { north: false, south: false, east: false, west: false };
  for (const [direction, dx, dz] of HORIZONTAL) {
    const other = neighbor(dx, 0, dz);
    result[direction] = other !== null && test(other, direction);
  }
  return result;
}

function gateConnects(other: BlockState, direction: Horizontal): boolean {
  if (!other.name.endsWith('_fence_gate')) return false;
  const facing = horizontal(other.properties['facing']);
  return facing !== null && facing !== direction && facing !== OPPOSITE[direction];
}

function asBooleans(sides: Record<Horizontal, boolean>): Record<string, string> {
  return { north: String(sides.north), south: String(sides.south), east: String(sides.east), west: String(sides.west) };
}

function wireSide(state: BlockState, direction: Horizontal, neighbor: NeighborLookup, solid: SolidTest): string {
  const [dx, dz] = OFFSETS[direction];
  const side = neighbor(dx, 0, dz);
  const above = neighbor(0, 1, 0);
  const sideSolid = side !== null && solid(side);
  if (above === null || !solid(above)) {
    const up = neighbor(dx, 1, dz);
    if (sideSolid && up?.name === 'redstone_wire') return 'up';
  }
  if (side !== null && (side.name === 'redstone_wire' || REDSTONE_COMPONENTS.test(side.name))) {
    if (side.name === 'repeater') {
      const facing = horizontal(side.properties['facing']);
      return facing === direction || facing === OPPOSITE[direction] ? 'side' : 'none';
    }
    return 'side';
  }
  if (!sideSolid && neighbor(dx, -1, dz)?.name === 'redstone_wire') return 'side';
  return state.properties[direction] === 'up' ? 'up' : 'none';
}

export function deriveLegacyState(state: BlockState, neighbor: NeighborLookup, solid: SolidTest): BlockState | null {
  const name = state.name;
  if (name.endsWith('_stairs')) return withProperties(state, { shape: stairsShape(state, neighbor) });
  if (isFence(name)) {
    const nether = name === 'nether_brick_fence';
    const sides = connections((other, direction) => (isFence(other.name) && (other.name === 'nether_brick_fence') === nether) || gateConnects(other, direction) || solid(other), neighbor);
    return withProperties(state, asBooleans(sides));
  }
  if (isPane(name)) {
    const sides = connections((other) => isPane(other.name) || isGlass(other.name) || isWall(other.name) || solid(other), neighbor);
    return withProperties(state, asBooleans(sides));
  }
  if (isWall(name)) {
    const sides = connections((other, direction) => isWall(other.name) || gateConnects(other, direction) || isPane(other.name) || solid(other), neighbor);
    const straight = (sides.north && sides.south && !sides.east && !sides.west) || (sides.east && sides.west && !sides.north && !sides.south);
    const above = neighbor(0, 1, 0);
    return withProperties(state, { ...asBooleans(sides), up: String(!straight || (above !== null && above.name !== 'air')) });
  }
  if (name.endsWith('_door')) {
    const upper = state.properties['half'] === 'upper';
    const other = neighbor(0, upper ? -1 : 1, 0);
    if (other?.name !== name) return null;
    if (upper) {
      const changes: Record<string, string> = {};
      for (const key of ['facing', 'open']) {
        const value = other.properties[key];
        if (value !== undefined) changes[key] = value;
      }
      return withProperties(state, changes);
    }
    const hinge = other.properties['hinge'];
    const powered = other.properties['powered'];
    return withProperties(state, { ...(hinge === undefined ? {} : { hinge }), ...(powered === undefined ? {} : { powered }) });
  }
  if (name === 'redstone_wire') {
    const changes: Record<string, string> = {};
    for (const [direction] of HORIZONTAL) changes[direction] = wireSide(state, direction, neighbor, solid);
    return withProperties(state, changes);
  }
  if (SNOWY_BLOCKS.has(name)) {
    const above = neighbor(0, 1, 0);
    return withProperties(state, { snowy: String(above !== null && SNOW_COVERS.has(above.name)) });
  }
  return null;
}

export function needsLegacyDerivation(name: string): boolean {
  return name.endsWith('_stairs') || isFence(name) || isPane(name) || isWall(name) || name.endsWith('_door') || name === 'redstone_wire' || SNOWY_BLOCKS.has(name);
}
