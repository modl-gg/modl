import type { ChatSegment, ItemStackView, ReplayPlayerInfo } from '../../engine/ReplayPlayer';
import { GUI_COLORS, parseHexColor } from '../gui/colors';
import type { GuiPainter } from '../gui/painter';
import { CHAT_LINE_HEIGHT, CHAT_WIDTH, type ChatRow } from './chat-layout';
import { effectIconSprite, heartKindFor, isBeneficialEffect, isHungry } from './game-text';
import {
  armorPlacements,
  experienceProgressWidth,
  foodPlacements,
  heartLayout,
  hotbarItemY,
  hotbarSlotX,
  hudAnchors,
  type HudAnchors,
  type SpritePlacement,
} from './hud-layout';

const OBFUSCATION_GLYPHS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
const DEFAULT_MAX_HEALTH = 20;

export interface HudScene {
  readonly width: number;
  readonly bottom: number;
  readonly player: ReplayPlayerInfo | null;
  readonly chat: readonly ChatRow[];
  readonly chatBottom: number;
  readonly tick: number;
  readonly offhandSlot: boolean;
}

function placeAll(painter: GuiPainter, placements: readonly SpritePlacement[]): void {
  for (const placement of placements) painter.sprite(placement.sprites, placement.x, placement.y);
}

function hotbarStacks(player: ReplayPlayerInfo): ReadonlyMap<number, ItemStackView> {
  const stacks = new Map<number, ItemStackView>();
  for (const stack of player.inventory) {
    if (stack.slot >= 0 && stack.slot < 9) stacks.set(stack.slot, stack);
  }
  const selected = player.selectedSlot ?? 0;
  if (player.mainHand !== null && !stacks.has(selected)) stacks.set(selected, player.mainHand);
  return stacks;
}

function paintHotbar(painter: GuiPainter, anchors: HudAnchors, player: ReplayPlayerInfo, offhandSlot: boolean): void {
  const selected = player.selectedSlot ?? 0;
  const offhand = offhandSlot ? player.offhand : null;
  if (offhand !== null) {
    painter.sprite('hud/hotbar_offhand_left', anchors.hotbarLeft - 29, anchors.bottom - 23);
  }
  painter.sprite('hud/hotbar', anchors.hotbarLeft, anchors.hotbarTop);
  painter.sprite('hud/hotbar_selection', anchors.hotbarLeft - 1 + selected * 20, anchors.hotbarTop - 1);
  for (const [slot, stack] of hotbarStacks(player)) {
    painter.item(stack.item, hotbarSlotX(anchors, slot), hotbarItemY(anchors), stack.count, stack.enchanted);
  }
  if (offhand !== null) {
    painter.item(offhand.item, anchors.hotbarLeft - 26, hotbarItemY(anchors), offhand.count, offhand.enchanted);
  }
}

function hasHotbar(player: ReplayPlayerInfo): boolean {
  return player.selectedSlot !== null || player.inventory.length > 0;
}

function paintExperience(painter: GuiPainter, anchors: HudAnchors, width: number, player: ReplayPlayerInfo): void {
  if (player.xpProgress === null && player.xpLevel === null) return;
  painter.sprite('hud/experience_bar_background', anchors.hotbarLeft, anchors.experienceTop);
  painter.spriteClipped('hud/experience_bar_progress', anchors.hotbarLeft, anchors.experienceTop, experienceProgressWidth(player.xpProgress ?? 0));
  const level = player.xpLevel ?? 0;
  if (level > 0) {
    const label = String(level);
    painter.outlinedText(label, Math.floor((width - painter.textWidth(label)) / 2), anchors.levelTop, GUI_COLORS.experience);
  }
}

function paintStatus(painter: GuiPainter, anchors: HudAnchors, player: ReplayPlayerInfo): void {
  const left = anchors.hotbarLeft;
  let armorTop = anchors.statusTop - 10;
  if (player.health !== null) {
    const hearts = heartLayout({
      health: player.health,
      maxHealth: player.maxHealth ?? DEFAULT_MAX_HEALTH,
      absorption: player.absorption ?? 0,
      hardcore: player.hardcore,
      kind: heartKindFor(player.effects),
    }, left, anchors.statusTop);
    placeAll(painter, hearts.placements);
    armorTop = anchors.statusTop - (hearts.rows - 1) * hearts.rowHeight - 10;
  }
  if (player.armor !== null) placeAll(painter, armorPlacements(player.armor, left, armorTop));
  if (player.food !== null) placeAll(painter, foodPlacements(player.food, isHungry(player.effects), anchors.foodRight, anchors.statusTop));
}

function paintEffects(painter: GuiPainter, width: number, player: ReplayPlayerInfo): void {
  let beneficial = 0;
  let harmful = 0;
  for (const effect of player.effects) {
    const good = isBeneficialEffect(effect);
    const column = good ? (beneficial += 1) : (harmful += 1);
    const x = width - 25 * column;
    const y = good ? 1 : 27;
    painter.sprite('hud/effect_background', x, y);
    painter.sprite(effectIconSprite(effect), x + 3, y + 3);
  }
}

function obfuscated(text: string, tick: number): string {
  return Array.from(text, (character, index) => (character === ' ' ? ' ' : OBFUSCATION_GLYPHS.charAt((tick * 7 + index * 13) % OBFUSCATION_GLYPHS.length))).join('');
}

function paintSegment(painter: GuiPainter, segment: ChatSegment, x: number, y: number, tick: number): number {
  const color = parseHexColor(segment.color, GUI_COLORS.white);
  const text = segment.obfuscated ? obfuscated(segment.text, tick) : segment.text;
  const advance = painter.text(text, x, y, { color, bold: segment.bold, italic: segment.italic });
  if (segment.underlined) painter.fill(x, y + 8, advance, 1, 0xff000000 | color);
  if (segment.strikethrough) painter.fill(x, y + 3, advance, 1, 0xff000000 | color);
  return advance;
}

function paintChat(painter: GuiPainter, rows: readonly ChatRow[], bottom: number, tick: number): void {
  const context = painter.context;
  rows.forEach((row, index) => {
    const rowBottom = bottom - index * CHAT_LINE_HEIGHT;
    painter.fill(0, rowBottom - CHAT_LINE_HEIGHT, CHAT_WIDTH + 8, CHAT_LINE_HEIGHT, (Math.round(row.backgroundAlpha * 255) << 24) >>> 0);
    context.save();
    context.globalAlpha = row.textAlpha;
    let x = 4;
    for (const segment of row.segments) x += paintSegment(painter, segment, x, rowBottom - 8, tick);
    context.restore();
  });
}

export function paintHud(painter: GuiPainter, scene: HudScene): void {
  const anchors = hudAnchors(scene.width, scene.bottom);
  const player = scene.player;
  if (player !== null && player.gamemode !== 'spectator') {
    if (hasHotbar(player)) paintHotbar(painter, anchors, player, scene.offhandSlot);
    if (player.gamemode !== 'creative') {
      paintExperience(painter, anchors, scene.width, player);
      paintStatus(painter, anchors, player);
    }
  }
  if (player !== null) paintEffects(painter, scene.width, player);
  paintChat(painter, scene.chat, scene.chatBottom, scene.tick);
}
