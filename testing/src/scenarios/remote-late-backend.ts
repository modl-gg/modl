import { ConsolePatterns } from "../lib/contract";
import { defineScenario, type ScenarioContext } from "./framework";
import { expectJoinRefused } from "./support";

const BACKEND = "backend";
const PAPER = "paper";
const CONNECTION_REFUSED = /Connection to panel failed|try again|unavailable|connect/i;

export const remoteLateBackend = defineScenario({
  name: "remote-late-backend",
  description: "Paper in remote mode starts before the modl server, refuses logins, then finishes its late bootstrap",
  topologies: ["remote"],
  async run(t: ScenarioContext) {
    await t.step("the modl server is stopped", async () => {
      const stop = await t.env.stopGracefully(BACKEND);
      t.evidence("backend stop", stop);
    });

    const paperSince = await t.step(
      "Paper starts while the modl server is down",
      async () => {
        t.quitAllBots();
        const stop = await t.env.stopGracefully(PAPER);
        t.evidence("paper stop", stop);
        const log = t.env.log(PAPER);
        const since = await log.mark();
        await t.env.startExisting(PAPER);
        await t.env.waitForMinecraft(PAPER, since, "none");
        const unavailable = await log.waitFor(ConsolePatterns.remoteUnavailable, { since, timeoutMs: 120_000 });
        t.evidence("connection failure notice", unavailable.text);
        return since;
      },
      { timeoutMs: 420_000, allowConsoleErrors: true },
    );

    await t.step(
      "logins are refused while the modl server is unreachable",
      async () => {
        const reason = await expectJoinRefused(t, t.playerName("Wait"), 20_000);
        t.evidence("refusal", reason);
        t.expectMatch("the refusal explains the connection problem", reason, CONNECTION_REFUSED);
      },
      { allowConsoleErrors: true },
    );

    await t.step(
      "starting the modl server completes Paper's late bootstrap",
      async () => {
        await t.env.startExisting(BACKEND);
        await t.env.waitForPanel();
        const booted = await t.env.log(PAPER).waitFor(ConsolePatterns.pluginBooted, { since: paperSince, timeoutMs: 240_000 });
        t.evidence("late bootstrap", booted.text);
      },
      { timeoutMs: 420_000, allowConsoleErrors: true },
    );

    await t.step("players can join after recovery", async () => {
      const bot = await t.bot(t.playerName("Late"));
      t.check("the player joined", bot.connected);
    });
  },
});
