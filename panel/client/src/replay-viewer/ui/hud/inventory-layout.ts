import type { ItemStackView, ReplayPlayerInfo } from '../../engine/ReplayPlayer';
import { sameList } from '../gui/same-list.ts';

export const INVENTORY_WIDTH = 176;
export const INVENTORY_HEIGHT = 166;
const SLOT_PITCH = 18;
const HOTBAR_SLOTS = 9;
const MAIN_FIRST_SLOT = 9;
const MAIN_LAST_SLOT = 35;

export interface PlacedStack {
  readonly stack: ItemStackView;
  readonly x: number;
  readonly y: number;
}

function inventoryPosition(slot: number): { readonly x: number; readonly y: number } | null {
  if (slot >= 0 && slot < HOTBAR_SLOTS) return { x: 8 + slot * SLOT_PITCH, y: 142 };
  if (slot >= MAIN_FIRST_SLOT && slot <= MAIN_LAST_SLOT) {
    const index = slot - MAIN_FIRST_SLOT;
    return { x: 8 + (index % HOTBAR_SLOTS) * SLOT_PITCH, y: 84 + Math.floor(index / HOTBAR_SLOTS) * SLOT_PITCH };
  }
  return null;
}

export function placedStacks(player: ReplayPlayerInfo, hasOffhandSlot: boolean): PlacedStack[] {
  const placed: PlacedStack[] = [];
  const taken = new Set<number>();
  for (const stack of player.inventory) {
    const position = inventoryPosition(stack.slot);
    if (position === null || taken.has(stack.slot)) continue;
    taken.add(stack.slot);
    placed.push({ stack, ...position });
  }
  const selected = player.selectedSlot ?? 0;
  if (player.mainHand !== null && !taken.has(selected)) {
    const position = inventoryPosition(selected);
    if (position !== null) placed.push({ stack: player.mainHand, ...position });
  }
  const armor = [player.armorSlots.head, player.armorSlots.chest, player.armorSlots.legs, player.armorSlots.feet];
  armor.forEach((stack, index) => {
    if (stack !== null) placed.push({ stack, x: 8, y: 8 + index * SLOT_PITCH });
  });
  if (hasOffhandSlot && player.offhand !== null) placed.push({ stack: player.offhand, x: 77, y: 62 });
  return placed;
}

export interface EmptySlotHint {
  readonly sprite: string;
  readonly texture: string;
  readonly x: number;
  readonly y: number;
}

const ARMOR_HINTS = ['helmet', 'chestplate', 'leggings', 'boots'] as const;

export function emptySlotHints(player: ReplayPlayerInfo, hasOffhandSlot: boolean): EmptySlotHint[] {
  const armor = [player.armorSlots.head, player.armorSlots.chest, player.armorSlots.legs, player.armorSlots.feet];
  const hints: EmptySlotHint[] = ARMOR_HINTS.flatMap((name, index) => (armor[index] === null
    ? [{ sprite: `container/slot/${name}`, texture: `item/empty_armor_slot_${name}`, x: 8, y: 8 + index * SLOT_PITCH }]
    : []));
  if (hasOffhandSlot && player.offhand === null) hints.push({ sprite: 'container/slot/shield', texture: 'item/empty_armor_slot_shield', x: 77, y: 62 });
  return hints;
}

export function stackAt(stacks: readonly PlacedStack[], x: number, y: number): PlacedStack | null {
  return stacks.find((placed) => x >= placed.x - 1 && x < placed.x + 17 && y >= placed.y - 1 && y < placed.y + 17) ?? null;
}

export interface InventoryContents {
  readonly stacks: readonly PlacedStack[];
  readonly hints: readonly EmptySlotHint[];
}

export function inventoryContents(player: ReplayPlayerInfo, hasOffhandSlot: boolean): InventoryContents {
  return { stacks: placedStacks(player, hasOffhandSlot), hints: emptySlotHints(player, hasOffhandSlot) };
}

function samePlaced(left: PlacedStack, right: PlacedStack): boolean {
  return left.x === right.x
    && left.y === right.y
    && left.stack.item === right.stack.item
    && left.stack.count === right.stack.count
    && left.stack.enchanted === right.stack.enchanted;
}

function sameHint(left: EmptySlotHint, right: EmptySlotHint): boolean {
  return left.sprite === right.sprite && left.x === right.x && left.y === right.y;
}

export function sameInventoryContents(left: InventoryContents, right: InventoryContents): boolean {
  return sameList(left.stacks, right.stacks, samePlaced) && sameList(left.hints, right.hints, sameHint);
}
