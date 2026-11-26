import type { LeakIndex } from "../lib/console-leaks";
import { auditConsole, type ConsoleAudit } from "../lib/console-policy";
import { PlayerCommands } from "../lib/contract";
import type { ConsoleEntry } from "../lib/log-entries";
import { timestampKey } from "../lib/logs";
import { errorMessage } from "../lib/util";
import { defineScenario, type ScenarioContext } from "./framework";

const BOOT_GRACE_NS = 20_000_000_000n;

function bootWindow(t: ScenarioContext, entries: readonly ConsoleEntry[]): ConsoleEntry[] {
  const bootedAt = t.env.state.bootedAt;
  if (!bootedAt) return [...entries];
  const limit = timestampKey(bootedAt) + BOOT_GRACE_NS;
  return entries.filter((entry) => timestampKey(entry.ts) <= limit);
}

function reportAudit(t: ScenarioContext, label: string, audit: ConsoleAudit, checkModlLines: boolean): void {
  t.evidence(`${label}: modl lines`, audit.expectedModl.map((finding) => `[${finding.rule ?? ""}] ${finding.text}`));
  t.check(`${label}: no server library output`, audit.noise.length === 0, audit.noise);
  t.check(`${label}: no server warning about modl`, audit.serverNags.length === 0, audit.serverNags);
  t.check(`${label}: no raw formatting codes`, audit.rawFormatting.length === 0, audit.rawFormatting);
  if (checkModlLines) t.check(`${label}: only expected modl lines`, audit.unexpectedModl.length === 0, audit.unexpectedModl);
}

async function auditServers(t: ScenarioContext, leaks: LeakIndex, label: string, select: (server: string) => Promise<ConsoleEntry[]>): Promise<void> {
  for (const server of t.env.consoleServerNames()) {
    reportAudit(t, `${server} ${label}`, auditConsole(server, await select(server), leaks), true);
  }
}

export const consoleAllowlist = defineScenario({
  name: "console-allowlist",
  description: "the Minecraft consoles show only expected modl lines during up and during play, and no output from the bundled server libraries",
  topologies: ["standalone", "network", "network-bungee", "remote"],
  async run(t: ScenarioContext) {
    const leaks = await t.step("the server library packages are indexed from the plugin jar", async () => {
      const index = await t.env.leakIndex();
      t.evidence("server-only packages", index.packages.size);
      t.check("the plugin jar carries the server payload", index.packages.size > 0);
      return index;
    });

    await t.step("boot consoles show only expected modl lines", async () =>
      auditServers(t, leaks, "boot", async (server) => bootWindow(t, await t.env.entries(server))),
    );

    await t.step("player activity adds no unexpected modl lines", async () => {
      const marks = await t.env.markAll();
      const bot = await t.bot(t.playerName("Quiet"));
      const other = await t.bot(t.playerName("Calm"));
      await bot.runCommand(`hello from ${bot.username}`, { timeoutMs: 3000 });
      for (const menu of [PlayerCommands.standing, PlayerCommands.report(other.username)]) {
        await bot.openWindow(menu).catch((error: unknown) => t.note(`${menu}: ${errorMessage(error)}`));
        if (bot.readWindow()) bot.closeWindow();
      }
      t.quitBot(bot.username);
      t.quitBot(other.username);
      await t.sleep(4000);
      await auditServers(t, leaks, "activity", async (server) => t.env.entries(server, marks[server]));
    });

    await t.step("the complete consoles carry no server library output", async () => {
      for (const server of t.env.consoleServerNames()) {
        reportAudit(t, `${server} full log`, auditConsole(server, await t.env.entries(server), leaks), false);
      }
    });
  },
});
