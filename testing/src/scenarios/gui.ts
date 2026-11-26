import type { TestBot, WindowSlot, WindowView } from "../lib/bots/bot";
import { frozeBroadcast, HotbarItems, PlayerCommands, StaffMessages } from "../lib/contract";
import { defineScenario, type ScenarioContext } from "./framework";
import { sleep } from "../lib/util";
import { awaitHotbarSlot, linkedOwnerBot, passOptionalReplayStep } from "./support";

function summarize(view: WindowView) {
  return {
    title: view.title,
    containerSlots: view.containerSlots,
    items: view.slots.slice(0, 14).map((slot) => ({ slot: slot.slot, name: slot.customName ?? slot.displayName, lore: slot.lore.slice(0, 3) })),
  };
}

function requireSlot(t: ScenarioContext, bot: TestBot, pattern: RegExp): WindowSlot {
  const slot = bot.findSlot(pattern);
  t.require(slot, `no slot matching ${pattern.toString()} in "${bot.readWindow()?.title ?? "no window"}"`);
  return slot;
}

const DURATION_PART = /(\d+)([dhms])/g;
const UNIT_SECONDS: Readonly<Record<string, number>> = { d: 86_400, h: 3600, m: 60, s: 1 };

function durationSeconds(text: string): number | null {
  const parts = [...text.matchAll(DURATION_PART)];
  if (parts.length === 0) return null;
  return parts.reduce((total, [, amount, unit]) => total + Number(amount) * (UNIT_SECONDS[unit ?? ""] ?? 0), 0);
}

function loreDuration(slot: WindowSlot, label: RegExp): number | null {
  const line = slot.lore.find((entry) => label.test(entry));
  return line === undefined ? null : durationSeconds(line.replace(label, ""));
}

function isNamed(slot: WindowSlot): boolean {
  return slot.customName !== null && slot.customName !== "";
}

function actionableSlot(view: WindowView): WindowSlot | undefined {
  return view.slots.find((slot) => isNamed(slot) && slot.lore.some((line) => /click/i.test(line))) ?? view.slots.find(isNamed);
}

export const gui = defineScenario({
  name: "gui",
  description: "the standing, report and punish menus open, render readable items and react to clicks, and the staff hotbar freezes a target",
  topologies: ["standalone", "network", "network-bungee", "remote"],
  async run(t: ScenarioContext) {
    const player = await t.step("a player joins", async () => t.bot(t.playerName("Gui")));
    const target = await t.step("a target joins", async () => t.bot(t.playerName("GuiT")));

    await t.step("the standing menu opens with readable items", async () => {
      const view = await player.openWindow(PlayerCommands.standing);
      t.evidence("standing menu", summarize(view));
      t.check("the menu has items", view.slots.length > 0);
      t.check("items carry names or lore", view.slots.some((slot) => (slot.customName ?? "").length > 0 || slot.lore.length > 0));
      player.closeWindow();
    });

    await t.step("the report menu opens and a category click opens the next menu", async () => {
      const view = await player.openWindow(PlayerCommands.report(target.username));
      t.evidence("report menu", summarize(view));
      const category = requireSlot(t, player, /Cheating/i);
      await player.clickAndAwaitWindow(category.slot);
      await passOptionalReplayStep(player);
      const details = player.readWindow();
      t.evidence("report details menu", details ? summarize(details) : null);
      t.check("the details menu offers to skip details", player.findSlot(/Skip Details/i) !== null);
      player.closeWindow();
    });

    await t.step("the punish menu opens for the linked owner and responds to a click", async () => {
      const owner = await linkedOwnerBot(t);
      const punishSince = owner.lastSeq;
      const view = await owner.openWindow(PlayerCommands.punish(target.username)).catch((error: unknown) => {
        t.evidence("punish command replies", owner.messagesSince(punishSince).map((message) => message.text));
        throw error;
      });
      t.evidence("punish menu", summarize(view));
      t.check("the punish menu has items", view.slots.length > 0);
      const slot = actionableSlot(view);
      t.require(slot, "the punish menu has no named item to click");
      const since = owner.lastSeq;
      const next = await owner.clickAndAwaitWindow(slot.slot, 5000).catch(() => null);
      const replies = owner.messagesSince(since).map((message) => message.text);
      t.evidence("punish click result", { clicked: slot.customName, nextWindow: next ? summarize(next) : null, replies });
      t.check("the click opened a menu or replied", next !== null || replies.length > 0);
      if (owner.readWindow()) owner.closeWindow();
    });

    await t.step("the online players menu counts the live session in the total playtime", async () => {
      const owner = await linkedOwnerBot(t);
      await sleep(3000);
      await owner.openWindow(PlayerCommands.staffMenu);
      const tab = requireSlot(t, owner, /^Online Players$/);
      const view = await owner.clickAndAwaitWindow(tab.slot);
      t.evidence("online players menu", summarize(view));
      const entry = requireSlot(t, owner, new RegExp(`^${target.username}$`));
      const session = loreDuration(entry, /^Session:\s*/);
      const total = loreDuration(entry, /^Total Playtime:\s*/);
      t.evidence("target playtime lore", { lore: entry.lore, session, total });
      t.check("the session is shown and at least one second", session !== null && session >= 1);
      t.check("the total playtime includes the live session", session !== null && total !== null && total >= session);
      owner.closeWindow();
    });

    await t.step("the staff hotbar freezes the target through the staff action gate", async () => {
      const owner = await linkedOwnerBot(t);
      const targetSince = target.lastSeq;
      const ownerSince = owner.lastSeq;
      await owner.runCommand(PlayerCommands.target(target.username), { expect: StaffMessages.targeting, timeoutMs: 10_000 });
      const freeze = await awaitHotbarSlot(t, owner, HotbarItems.freezeTarget);
      await owner.useHotbarSlot(freeze.slot);
      await target.awaitMessage(StaffMessages.frozen, 10_000, targetSince);
      const broadcast = await owner.awaitMessage(frozeBroadcast(target.username), 10_000, ownerSince).catch(() => null);
      t.evidence("hotbar freeze", { owner: owner.messagesSince(ownerSince).map((message) => message.text) });
      t.check("the freeze from the hotbar is announced to staff", broadcast !== null);

      const releaseSince = target.lastSeq;
      await owner.useHotbarSlot((await awaitHotbarSlot(t, owner, HotbarItems.freezeTarget)).slot);
      await target.awaitMessage(StaffMessages.unfrozen, 10_000, releaseSince);
      await owner.runCommand(PlayerCommands.staffMode, { expect: StaffMessages.staffModeDisabled, timeoutMs: 8000 });
    });
  },
});
