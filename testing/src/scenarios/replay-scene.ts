import { readFileSync } from "node:fs";
import { join } from "node:path";
import { HARNESS_ROOT } from "../lib/paths";
import type { LineName } from "../lib/state";

export interface SceneBlock {
  x: number;
  dy: number;
  z: number;
  name: string;
  properties: Record<string, string>;
  modernOnly?: boolean;
}

export interface ReplayScene {
  baseY: number;
  minY: number;
  height: number;
  legacy: boolean;
  commands: string[];
  playerCommands: string[];
  outfit: readonly string[];
  blocks: SceneBlock[];
  item(x: number, y: number, z: number, item: string): string;
}

const SCENE_DIR = join(HARNESS_ROOT, "fixtures", "replay-scene");

const WOOL = ["white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"];

function block(x: number, dy: number, z: number, name: string, properties: Record<string, string> = {}, modernOnly = false): SceneBlock {
  return { x, dy, z, name: `minecraft:${name}`, properties, modernOnly };
}

const BLOCKS: SceneBlock[] = [
  block(0, 0, 0, "oak_stairs", { facing: "north", half: "bottom" }),
  block(2, 0, 0, "oak_stairs", { facing: "east", half: "bottom" }),
  block(4, 0, 0, "oak_stairs", { facing: "south", half: "bottom" }),
  block(6, 0, 0, "oak_stairs", { facing: "west", half: "bottom" }),
  block(8, 0, 0, "oak_stairs", { facing: "north", half: "top" }),
  block(0, 0, 2, "stone_slab", { type: "bottom" }, true),
  block(2, 0, 2, "stone_slab", { type: "top" }, true),
  block(4, 0, 2, "stone_slab", { type: "double" }, true),
  ...WOOL.map((color, index) => block(index, 0, 4, `${color}_wool`)),
  block(0, 0, 6, "glass"),
  block(3, 0, 6, "glass_pane", { east: "true", west: "true" }, true),
  block(0, 0, 8, "oak_log", { axis: "y" }),
  block(2, 0, 8, "oak_log", { axis: "x" }),
  block(4, 0, 8, "oak_log", { axis: "z" }),
  block(6, 0, 8, "birch_log", { axis: "y" }),
  block(1, 0, 10, "oak_fence", { east: "true", west: "true" }, true),
  block(1, 0, 13, "water", { level: "0" }),
  block(0, 0, 16, "oak_leaves", { persistent: "true" }, true),
  block(12, 0, 16, "short_grass"),
  block(0, 0, 19, "chest", { facing: "south" }),
  block(5, 0, 19, "torch"),
  block(7, 0, 19, "wall_torch", { facing: "south" }, true),
  block(13, 0, 19, "oak_door", { half: "lower", facing: "south" }),
  block(13, 1, 19, "oak_door", { half: "upper" }),
];

const LEGACY_BLOCKS: SceneBlock[] = [
  block(0, 0, 2, "smooth_stone_slab", { type: "bottom" }),
  block(2, 0, 2, "smooth_stone_slab", { type: "top" }),
  block(3, 0, 6, "glass_pane"),
  block(1, 0, 10, "oak_fence"),
];

const MODERN_OUTFIT: readonly string[] = ["diamond_helmet", "iron_chestplate", "golden_leggings", "leather_boots", "diamond_sword"];

function lines(file: string): string[] {
  return readFileSync(join(SCENE_DIR, file), "utf8").split("\n").map((line) => line.trim()).filter((line) => line.length > 0);
}

export function replayScene(line: LineName): ReplayScene {
  const legacy = line === "legacy";
  return {
    baseY: legacy ? 4 : -60,
    minY: legacy ? 0 : -64,
    height: legacy ? 256 : 384,
    legacy,
    commands: lines(legacy ? "scene-legacy.txt" : "scene-modern.txt"),
    playerCommands: lines(legacy ? "scene-legacy-player.txt" : "scene-modern-player.txt"),
    outfit: legacy ? [] : MODERN_OUTFIT,
    blocks: legacy ? [...BLOCKS.filter((entry) => entry.modernOnly !== true), ...LEGACY_BLOCKS] : BLOCKS,
    item: legacy
      ? (x, y, z, item) => `summon Item ${String(x)} ${String(y)} ${String(z)} {Item:{id:minecraft:${item},Count:1},PickupDelay:32767,Age:-32768}`
      : (x, y, z, item) => `summon item ${String(x)} ${String(y)} ${String(z)} {Item:{id:"minecraft:${item}",count:1},PickupDelay:32767s,Age:-32768s}`,
  };
}
