import type { BlockState } from './BlockState.ts';

type LegacyMapping = (meta: number) => BlockState;

const COLORS = ['white', 'orange', 'magenta', 'light_blue', 'yellow', 'lime', 'pink', 'gray', 'light_gray', 'cyan', 'purple', 'blue', 'brown', 'green', 'red', 'black'];
const WOODS = ['oak', 'spruce', 'birch', 'jungle', 'acacia', 'dark_oak'];
const STAIR_FACING = ['east', 'west', 'south', 'north'];
const DOOR_FACING = ['east', 'south', 'west', 'north'];
const FURNACE_FACING: Readonly<Record<number, string>> = { 2: 'north', 3: 'south', 4: 'west', 5: 'east' };
const PUMPKIN_FACING = ['south', 'west', 'north', 'east'];
const TORCH_FACING: Readonly<Record<number, string>> = { 1: 'east', 2: 'west', 3: 'south', 4: 'north' };
const LOG_AXIS = ['y', 'x', 'z', 'y'];

function state(name: string, properties: Record<string, string> = {}): BlockState {
  return { name, properties };
}

function plain(name: string, properties: Record<string, string> = {}): LegacyMapping {
  return () => state(name, properties);
}

function variants(names: readonly string[], mask = 15): LegacyMapping {
  return (meta) => state(names[meta & mask] ?? names[0] ?? 'air');
}

function colored(suffix: string): LegacyMapping {
  return (meta) => state(`${COLORS[meta & 15] ?? 'white'}_${suffix}`);
}

function stairs(name: string): LegacyMapping {
  return (meta) => state(name, { facing: STAIR_FACING[meta & 3] ?? 'east', half: (meta & 4) === 0 ? 'bottom' : 'top', shape: 'straight', waterlogged: 'false' });
}

function slab(names: readonly string[]): LegacyMapping {
  return (meta) => state(names[meta & 7] ?? names[0] ?? 'stone_slab', { type: (meta & 8) === 0 ? 'bottom' : 'top', waterlogged: 'false' });
}

function doubleSlab(names: readonly string[]): LegacyMapping {
  return (meta) => state(names[meta & 7] ?? names[0] ?? 'stone_slab', { type: 'double', waterlogged: 'false' });
}

function log(names: readonly string[]): LegacyMapping {
  return (meta) => {
    const wood = names[meta & 3] ?? names[0] ?? 'oak';
    if ((meta & 12) === 12) return state(`${wood}_wood`, { axis: 'y' });
    return state(`${wood}_log`, { axis: LOG_AXIS[(meta >> 2) & 3] ?? 'y' });
  };
}

function leaves(names: readonly string[]): LegacyMapping {
  return (meta) => state(`${names[meta & 3] ?? 'oak'}_leaves`, { distance: '1', persistent: 'false', waterlogged: 'false' });
}

function fluid(name: string): LegacyMapping {
  return (meta) => state(name, { level: String(meta & 15) });
}

function door(name: string): LegacyMapping {
  return (meta) => {
    if ((meta & 8) !== 0) return state(name, { half: 'upper', hinge: (meta & 1) === 0 ? 'left' : 'right', powered: String((meta & 2) !== 0), facing: 'east', open: 'false' });
    return state(name, { half: 'lower', facing: DOOR_FACING[meta & 3] ?? 'east', open: String((meta & 4) !== 0), hinge: 'left', powered: 'false' });
  };
}

function facingBlock(name: string): LegacyMapping {
  return (meta) => state(name, { facing: FURNACE_FACING[meta & 7] ?? 'north' });
}

function torch(floor: string, wall: string, lit?: string): LegacyMapping {
  return (meta) => {
    const facing = TORCH_FACING[meta & 7];
    const properties: Record<string, string> = lit === undefined ? {} : { lit };
    return facing === undefined ? state(floor, properties) : state(wall, { ...properties, facing });
  };
}

function aged(name: string, max: number): LegacyMapping {
  return (meta) => state(name, { age: String(Math.min(max, meta & 15)) });
}

function fence(name: string): LegacyMapping {
  return () => state(name, { north: 'false', south: 'false', east: 'false', west: 'false', waterlogged: 'false' });
}

function doublePlant(): LegacyMapping {
  const names = ['sunflower', 'lilac', 'tall_grass', 'large_fern', 'rose_bush', 'peony'];
  return (meta) => ((meta & 8) !== 0 ? state('sunflower', { half: 'upper' }) : state(names[meta & 7] ?? 'tall_grass', { half: 'lower' }));
}

const WOOD_SLABS = WOODS.map((wood) => `${wood}_slab`);
const STONE_SLABS = ['smooth_stone_slab', 'sandstone_slab', 'petrified_oak_slab', 'cobblestone_slab', 'brick_slab', 'stone_brick_slab', 'nether_brick_slab', 'quartz_slab'];

const LEGACY_BLOCKS: Readonly<Record<number, LegacyMapping>> = {
  0: plain('air'),
  1: variants(['stone', 'granite', 'polished_granite', 'diorite', 'polished_diorite', 'andesite', 'polished_andesite'], 7),
  2: plain('grass_block', { snowy: 'false' }),
  3: variants(['dirt', 'coarse_dirt', 'podzol'], 3),
  4: plain('cobblestone'),
  5: (meta) => state(`${WOODS[meta & 7] ?? 'oak'}_planks`),
  6: (meta) => state(`${WOODS[meta & 7] ?? 'oak'}_sapling`, { stage: '0' }),
  7: plain('bedrock'),
  8: fluid('water'),
  9: fluid('water'),
  10: fluid('lava'),
  11: fluid('lava'),
  12: variants(['sand', 'red_sand'], 1),
  13: plain('gravel'),
  14: plain('gold_ore'),
  15: plain('iron_ore'),
  16: plain('coal_ore'),
  17: log(['oak', 'spruce', 'birch', 'jungle']),
  18: leaves(['oak', 'spruce', 'birch', 'jungle']),
  19: variants(['sponge', 'wet_sponge'], 1),
  20: plain('glass'),
  21: plain('lapis_ore'),
  22: plain('lapis_block'),
  23: facingBlock('dispenser'),
  24: variants(['sandstone', 'chiseled_sandstone', 'cut_sandstone'], 3),
  25: plain('note_block'),
  26: plain('red_bed'),
  27: plain('powered_rail'),
  28: plain('detector_rail'),
  29: facingBlock('sticky_piston'),
  30: plain('cobweb'),
  31: variants(['dead_bush', 'short_grass', 'fern'], 3),
  32: plain('dead_bush'),
  33: facingBlock('piston'),
  35: colored('wool'),
  37: plain('dandelion'),
  38: variants(['poppy', 'blue_orchid', 'allium', 'azure_bluet', 'red_tulip', 'orange_tulip', 'white_tulip', 'pink_tulip', 'oxeye_daisy']),
  39: plain('brown_mushroom'),
  40: plain('red_mushroom'),
  41: plain('gold_block'),
  42: plain('iron_block'),
  43: doubleSlab(STONE_SLABS),
  44: slab(STONE_SLABS),
  45: plain('bricks'),
  46: plain('tnt'),
  47: plain('bookshelf'),
  48: plain('mossy_cobblestone'),
  49: plain('obsidian'),
  50: torch('torch', 'wall_torch'),
  51: plain('fire'),
  52: plain('spawner'),
  53: stairs('oak_stairs'),
  54: facingBlock('chest'),
  55: (meta) => state('redstone_wire', { power: String(meta & 15), north: 'none', south: 'none', east: 'none', west: 'none' }),
  56: plain('diamond_ore'),
  57: plain('diamond_block'),
  58: plain('crafting_table'),
  59: aged('wheat', 7),
  60: (meta) => state('farmland', { moisture: String(meta & 7) }),
  61: (meta) => state('furnace', { facing: FURNACE_FACING[meta & 7] ?? 'north', lit: 'false' }),
  62: (meta) => state('furnace', { facing: FURNACE_FACING[meta & 7] ?? 'north', lit: 'true' }),
  63: (meta) => state('oak_sign', { rotation: String(meta & 15) }),
  64: door('oak_door'),
  65: facingBlock('ladder'),
  66: plain('rail'),
  67: stairs('cobblestone_stairs'),
  68: facingBlock('oak_wall_sign'),
  69: plain('lever'),
  70: plain('stone_pressure_plate'),
  71: door('iron_door'),
  72: plain('oak_pressure_plate'),
  73: plain('redstone_ore', { lit: 'false' }),
  74: plain('redstone_ore', { lit: 'true' }),
  75: torch('redstone_torch', 'redstone_wall_torch', 'false'),
  76: torch('redstone_torch', 'redstone_wall_torch', 'true'),
  77: plain('stone_button'),
  78: (meta) => state('snow', { layers: String((meta & 7) + 1) }),
  79: plain('ice'),
  80: plain('snow_block'),
  81: aged('cactus', 15),
  82: plain('clay'),
  83: aged('sugar_cane', 15),
  84: plain('jukebox'),
  85: fence('oak_fence'),
  86: (meta) => state('carved_pumpkin', { facing: PUMPKIN_FACING[meta & 3] ?? 'south' }),
  87: plain('netherrack'),
  88: plain('soul_sand'),
  89: plain('glowstone'),
  90: plain('nether_portal'),
  91: (meta) => state('jack_o_lantern', { facing: PUMPKIN_FACING[meta & 3] ?? 'south' }),
  92: plain('cake'),
  93: (meta) => state('repeater', { facing: PUMPKIN_FACING[meta & 3] ?? 'south', delay: String((meta >> 2) + 1), powered: 'false', locked: 'false' }),
  94: (meta) => state('repeater', { facing: PUMPKIN_FACING[meta & 3] ?? 'south', delay: String((meta >> 2) + 1), powered: 'true', locked: 'false' }),
  95: colored('stained_glass'),
  96: plain('oak_trapdoor'),
  97: plain('infested_stone'),
  98: variants(['stone_bricks', 'mossy_stone_bricks', 'cracked_stone_bricks', 'chiseled_stone_bricks'], 3),
  99: plain('brown_mushroom_block'),
  100: plain('red_mushroom_block'),
  101: fence('iron_bars'),
  102: fence('glass_pane'),
  103: plain('melon'),
  104: aged('pumpkin_stem', 7),
  105: aged('melon_stem', 7),
  106: plain('vine'),
  107: plain('oak_fence_gate'),
  108: stairs('brick_stairs'),
  109: stairs('stone_brick_stairs'),
  110: plain('mycelium'),
  111: plain('lily_pad'),
  112: plain('nether_bricks'),
  113: fence('nether_brick_fence'),
  114: stairs('nether_brick_stairs'),
  115: aged('nether_wart', 3),
  116: plain('enchanting_table'),
  117: plain('brewing_stand'),
  118: plain('cauldron'),
  119: plain('end_portal'),
  120: plain('end_portal_frame'),
  121: plain('end_stone'),
  122: plain('dragon_egg'),
  123: plain('redstone_lamp', { lit: 'false' }),
  124: plain('redstone_lamp', { lit: 'true' }),
  125: doubleSlab(WOOD_SLABS),
  126: slab(WOOD_SLABS),
  127: plain('cocoa'),
  128: stairs('sandstone_stairs'),
  129: plain('emerald_ore'),
  130: facingBlock('ender_chest'),
  131: plain('tripwire_hook'),
  132: plain('tripwire'),
  133: plain('emerald_block'),
  134: stairs('spruce_stairs'),
  135: stairs('birch_stairs'),
  136: stairs('jungle_stairs'),
  137: plain('command_block'),
  138: plain('beacon'),
  139: (meta) => state((meta & 1) === 0 ? 'cobblestone_wall' : 'mossy_cobblestone_wall', { north: 'false', south: 'false', east: 'false', west: 'false', up: 'true', waterlogged: 'false' }),
  140: plain('flower_pot'),
  141: aged('carrots', 7),
  142: aged('potatoes', 7),
  143: plain('oak_button'),
  144: plain('skeleton_skull'),
  145: plain('anvil'),
  146: facingBlock('trapped_chest'),
  147: plain('light_weighted_pressure_plate'),
  148: plain('heavy_weighted_pressure_plate'),
  149: plain('comparator'),
  150: plain('comparator'),
  151: plain('daylight_detector'),
  152: plain('redstone_block'),
  153: plain('nether_quartz_ore'),
  154: plain('hopper'),
  155: (meta) => ((meta & 7) >= 2 ? state('quartz_pillar', { axis: ['y', 'x', 'z'][(meta & 7) - 2] ?? 'y' }) : state(meta === 1 ? 'chiseled_quartz_block' : 'quartz_block')),
  156: stairs('quartz_stairs'),
  157: plain('activator_rail'),
  158: facingBlock('dropper'),
  159: colored('terracotta'),
  160: (meta) => state(`${COLORS[meta & 15] ?? 'white'}_stained_glass_pane`, { north: 'false', south: 'false', east: 'false', west: 'false', waterlogged: 'false' }),
  161: leaves(['acacia', 'dark_oak']),
  162: log(['acacia', 'dark_oak']),
  163: stairs('acacia_stairs'),
  164: stairs('dark_oak_stairs'),
  165: plain('slime_block'),
  166: plain('barrier'),
  167: plain('iron_trapdoor'),
  168: variants(['prismarine', 'prismarine_bricks', 'dark_prismarine'], 3),
  169: plain('sea_lantern'),
  170: (meta) => state('hay_block', { axis: LOG_AXIS[(meta >> 2) & 3] ?? 'y' }),
  171: colored('carpet'),
  172: plain('terracotta'),
  173: plain('coal_block'),
  174: plain('packed_ice'),
  175: doublePlant(),
  176: plain('white_banner'),
  177: plain('white_wall_banner'),
  178: plain('daylight_detector'),
  179: variants(['red_sandstone', 'chiseled_red_sandstone', 'cut_red_sandstone'], 3),
  180: stairs('red_sandstone_stairs'),
  181: doubleSlab(['red_sandstone_slab']),
  182: slab(['red_sandstone_slab']),
  183: plain('spruce_fence_gate'),
  184: plain('birch_fence_gate'),
  185: plain('jungle_fence_gate'),
  186: plain('dark_oak_fence_gate'),
  187: plain('acacia_fence_gate'),
  188: fence('spruce_fence'),
  189: fence('birch_fence'),
  190: fence('jungle_fence'),
  191: fence('dark_oak_fence'),
  192: fence('acacia_fence'),
  193: door('spruce_door'),
  194: door('birch_door'),
  195: door('jungle_door'),
  196: door('acacia_door'),
  197: door('dark_oak_door'),
  198: plain('end_rod'),
  199: plain('chorus_plant'),
  200: plain('chorus_flower'),
  201: plain('purpur_block'),
  202: plain('purpur_pillar'),
  203: stairs('purpur_stairs'),
  204: doubleSlab(['purpur_slab']),
  205: slab(['purpur_slab']),
  206: plain('end_stone_bricks'),
  207: aged('beetroots', 3),
  208: plain('dirt_path'),
  209: plain('end_gateway'),
  212: plain('frosted_ice'),
  213: plain('magma_block'),
  214: plain('nether_wart_block'),
  215: plain('red_nether_bricks'),
  216: plain('bone_block'),
  218: facingBlock('observer'),
  251: colored('concrete'),
  252: colored('concrete_powder'),
};

export const UNKNOWN_BLOCK = 'modl_unknown';

export function legacyBlockState(globalId: number): BlockState {
  const id = globalId >> 4;
  const meta = globalId & 15;
  if (id >= 219 && id <= 234) return state(`${COLORS[id - 219] ?? 'white'}_shulker_box`);
  if (id >= 235 && id <= 250) return state(`${COLORS[id - 235] ?? 'white'}_glazed_terracotta`, { facing: PUMPKIN_FACING[meta & 3] ?? 'south' });
  const mapping = LEGACY_BLOCKS[id];
  return mapping === undefined ? state(UNKNOWN_BLOCK) : mapping(meta);
}
