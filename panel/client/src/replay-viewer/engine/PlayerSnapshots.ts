import type { EffectData, ItemStackData } from '../format/ReplayModel.ts';
import type { EquipmentState, ReplayTimeline } from './ReplayTimeline.ts';
import type { ArmorSlotsView, EffectView, ItemStackView, ReplayPlayerInfo } from './ReplayPlayer.ts';

const EMPTY_ARMOR: ArmorSlotsView = { head: null, chest: null, legs: null, feet: null };

function view(stack: ItemStackData | null | undefined, slot: number): ItemStackView | null {
  if (stack === null || stack === undefined) return null;
  return { slot, item: stack.item, count: stack.count, enchanted: stack.enchanted };
}

function inventorySlot(menuSlot: number): number | null {
  if (menuSlot >= 36 && menuSlot <= 44) return menuSlot - 36;
  if (menuSlot >= 9 && menuSlot <= 35) return menuSlot;
  return null;
}

function effects(list: readonly EffectData[] | undefined): EffectView[] {
  return (list ?? []).map((effect) => ({ effect: effect.effect, amplifier: effect.amplifier }));
}

function armorOf(equipment: EquipmentState): ArmorSlotsView {
  return {
    head: view(equipment.head, 39),
    chest: view(equipment.chest, 38),
    legs: view(equipment.legs, 37),
    feet: view(equipment.feet, 36),
  };
}

export class PlayerSnapshots {
  private readonly timeline: ReplayTimeline;
  private readonly hardcore: boolean;

  constructor(timeline: ReplayTimeline, hardcore: boolean) {
    this.timeline = timeline;
    this.hardcore = hardcore;
  }

  at(time: number): ReplayPlayerInfo[] {
    const result: ReplayPlayerInfo[] = [];
    const entityByUuid = new Map<string, number>();
    const presentByUuid = new Set<string>();
    for (const track of this.timeline.entities.values()) {
      if (track.uuid === null || track.type !== 'minecraft:player') continue;
      if (!entityByUuid.has(track.uuid) || track.present(time)) entityByUuid.set(track.uuid, track.id);
      if (track.present(time)) presentByUuid.add(track.uuid);
    }
    const uuids = new Set<string>([...this.timeline.players.keys(), ...entityByUuid.keys()]);
    for (const uuid of uuids) {
      const info = this.timeline.playerInfo(uuid, time);
      const entityId = entityByUuid.get(uuid) ?? -1;
      const track = this.timeline.entities.get(entityId);
      const isTarget = uuid === this.timeline.targetUuid || (entityId >= 0 && entityId === this.timeline.targetEntityId);
      const equipment = track?.equipment.at(time) ?? {};
      if (isTarget) {
        const target = this.timeline.targetAt(time);
        const inventory: ItemStackView[] = [];
        for (const [menuSlot, stack] of target.inventory) {
          const slot = inventorySlot(menuSlot);
          const stackView = slot === null ? null : view(stack, slot);
          if (stackView !== null) inventory.push(stackView);
        }
        inventory.sort((left, right) => left.slot - right.slot);
        const selected = target.selectedSlot;
        const targetEquipment = this.timeline.targetEquipment(time);
        result.push({
          entityId,
          uuid,
          name: info?.name ?? this.timeline.targetUuid ?? uuid,
          skinHash: info?.skinHash ?? null,
          slim: info?.slim ?? false,
          isTarget: true,
          present: presentByUuid.has(uuid),
          health: target.health,
          maxHealth: target.maxHealth,
          absorption: target.absorption,
          food: target.food,
          saturation: target.saturation,
          armor: target.armor,
          xpLevel: target.xpLevel,
          xpProgress: target.xpProgress,
          selectedSlot: selected,
          inventory,
          mainHand: selected === null ? view(equipment.mainhand, 0) : view(target.inventory.get(36 + selected), selected),
          offhand: view(targetEquipment.offhand ?? equipment.offhand, 40),
          armorSlots: target.inventory.size > 0 ? armorOf(targetEquipment) : armorOf(equipment),
          effects: effects(track?.effects.at(time)),
          gamemode: target.gameMode ?? info?.gameMode ?? null,
          hardcore: this.hardcore,
        });
        continue;
      }
      const metadataHealth = track?.metadata.at(time)?.health;
      result.push({
        entityId,
        uuid,
        name: info?.name ?? uuid,
        skinHash: info?.skinHash ?? null,
        slim: info?.slim ?? false,
        isTarget: false,
        present: presentByUuid.has(uuid),
        health: metadataHealth ?? null,
        maxHealth: null,
        absorption: null,
        food: null,
        saturation: null,
        armor: null,
        xpLevel: null,
        xpProgress: null,
        selectedSlot: null,
        inventory: [],
        mainHand: view(equipment.mainhand, 0),
        offhand: view(equipment.offhand, 40),
        armorSlots: track === undefined ? EMPTY_ARMOR : armorOf(equipment),
        effects: effects(track?.effects.at(time)),
        gamemode: info?.gameMode ?? null,
        hardcore: this.hardcore,
      });
    }
    return result.sort((left, right) => Number(right.isTarget) - Number(left.isTarget) || left.name.localeCompare(right.name));
  }
}
