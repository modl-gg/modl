import type { TestBot } from "../../lib/bots/bot";
import { PlayerCommands } from "../../lib/contract";
import type { RealClient } from "../footage/real-client";
import type { Narrator } from "../studio/pointer";

const SLOTS_PER_ROW = 9;
const AFTER_CLICK_MS = 300;
const CHOICES = [/Cheating/i, /Capture Replay/i, /Skip Details/i, /Confirm Report/i] as const;

export interface MenuStep {
  readonly rows: number;
  readonly slot: number;
}

export interface ReportPlan {
  readonly command: string;
  readonly steps: readonly MenuStep[];
}

function menuRows(title: string, containerSlots: number): number {
  if (containerSlots <= 0 || containerSlots % SLOTS_PER_ROW !== 0) throw new Error(`the report menu "${title}" is not a chest menu (${String(containerSlots)} slots)`);
  return containerSlots / SLOTS_PER_ROW;
}

export async function planReport(helper: TestBot, target: string): Promise<ReportPlan> {
  const command = PlayerCommands.report(target);
  await helper.openWindow(command);
  const steps: MenuStep[] = [];
  try {
    for (const [index, choice] of CHOICES.entries()) {
      const view = helper.readWindow();
      if (view === null) throw new Error(`${helper.username} has no report menu open`);
      const slot = helper.findSlot(choice);
      if (slot === null) throw new Error(`the report menu "${view.title}" has no item matching ${choice.toString()}`);
      steps.push({ rows: menuRows(view.title, view.containerSlots), slot: slot.slot });
      if (index < CHOICES.length - 1) await helper.clickAndAwaitWindow(slot.slot);
    }
  } finally {
    helper.closeWindow();
  }
  return { command, steps };
}

export async function chooseInMenu(game: RealClient, narrator: Narrator, step: MenuStep, next: MenuStep | undefined, readMs: number): Promise<void> {
  const point = game.layout.slot(step.rows, step.slot);
  await game.hover(point);
  await narrator.hold(readMs);
  if (next) await game.clickRevealingMenu(point, next.rows);
  else await game.click(point);
  await narrator.hold(AFTER_CLICK_MS);
}
