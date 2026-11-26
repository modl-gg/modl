import { ConsoleCommands, ConsolePatterns, Endpoints } from "../lib/contract";
import { defineScenario, type ScenarioContext } from "./framework";
import { ownerSession, reloadUnsupported } from "./support";

const COMMAND_REPLY = new RegExp(`${ConsolePatterns.commandWhileStarting.source}|${ConsolePatterns.modlVersionReply.source}`);

async function reloadOnce(t: ScenarioContext, round: number): Promise<void> {
  const log = t.env.log(t.coreServer);
  const serverConsole = t.env.console(t.coreServer);
  const since = await log.mark();
  await t.sendReload(t.coreServer);
  await log.waitFor(ConsolePatterns.bukkitReloadComplete, { since, timeoutMs: 120_000, requireRunning: true });
  const early = await serverConsole.run(ConsoleCommands.modlVersion, { expect: COMMAND_REPLY, timeoutMs: 10_000 });
  const bootedBeforeCommand = (await log.find(ConsolePatterns.pluginBooted, since)).length
    > (await log.find(ConsolePatterns.pluginBooted, early.since)).length;
  const booted = await log.waitFor(ConsolePatterns.pluginBooted, {
    since,
    timeoutMs: 240_000,
    requireRunning: true,
    failOn: ConsolePatterns.pluginFailed,
  });
  const late = await serverConsole.run(ConsoleCommands.modlVersion, { expect: ConsolePatterns.modlVersionReply, timeoutMs: 10_000 });
  t.evidence(`reload ${round} commands`, { early: early.matched, bootedBeforeCommand, late: late.matched });
  t.check(`reload ${round}: a modl command sent before the core is ready gets the connecting reply`,
    early.matched !== null && (ConsolePatterns.commandWhileStarting.test(early.matched) || bootedBeforeCommand),
    { reply: early.matched, bootedBeforeCommand });
  t.check(`reload ${round}: the same command works once the core is ready`, late.matched !== null, { lines: late.lines });
  const panelStarted = await log.find(ConsolePatterns.panelStarted, since);
  t.evidence(`reload ${round}`, { booted: booted.text, panelStarted: panelStarted.map((line) => line.text) });
  t.check(`reload ${round}: the embedded panel started again`, panelStarted.length > 0);
  await t.instance.waitHealthy(120_000);
  const setup = await t.instance.anonymousSession().request(Endpoints.setupStatus, { auth: "none" });
  t.check(`reload ${round}: the panel API answers`, setup.status === 200, { status: setup.status });
}

export const reloadTwice = defineScenario({
  name: "reload-twice",
  description: "/reload confirm twice on standalone Paper restarts the embedded panel in the same JVM and keeps it working",
  topologies: ["standalone"],
  unsupportedBy: reloadUnsupported,
  async run(t: ScenarioContext) {
    await t.step("the owner has a session before reloading", async () => ownerSession(t));
    await t.step("first /reload confirm", async () => reloadOnce(t, 1), { timeoutMs: 300_000 });
    await t.step("second /reload confirm", async () => reloadOnce(t, 2), { timeoutMs: 300_000 });

    await t.step("players, sessions and the panel work after two reloads", async () => {
      const bot = await t.bot(t.playerName("Rld"));
      const marker = `reload-${Date.now().toString(36)}`;
      const messages = await bot.runCommand(marker, { expect: new RegExp(marker), timeoutMs: 6000 });
      t.check("chat still works", messages.some((message) => message.text.includes(marker)));
      const profile = await t.instance.profile();
      t.check("the session from before the reloads is still valid", profile !== null, profile);
    });
  },
});
