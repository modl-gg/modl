import { ConsoleCommands, ConsolePatterns, PlayerCommands, StaffMessages } from "../lib/contract";
import { defineScenario, type ScenarioContext } from "./framework";
import { probeMovement, reloadUnsupported } from "./support";

const MEMBER = "survival";
const HELD_WITHIN_BLOCKS = 0.2;

export const memberReloadResyncsFreeze = defineScenario({
  name: "member-reload-resyncs-freeze",
  description: "a player frozen on a Paper server behind the proxy stays frozen after that server reloads, because the network hub resends the proxy's moderation state",
  topologies: ["network", "network-bungee"],
  unsupportedBy: reloadUnsupported,
  async run(t: ScenarioContext) {
    const target = await t.step("a target joins through the proxy", async () => t.bot(t.playerName("Frz")));

    await t.step(`the target moves to ${MEMBER}`, async () => {
      const switches = target.serverSwitches;
      target.chat(PlayerCommands.switchServer(MEMBER));
      await target.awaitServerSwitch(switches);
    });

    await t.step(`the proxy console freezes the target on ${MEMBER}`, async () => {
      const since = target.lastSeq;
      await t.consoleRun(ConsoleCommands.freeze(target.username), { server: t.coreServer, settleMs: 1500, timeoutMs: 10_000 });
      await target.awaitMessage(StaffMessages.frozen, 10_000, since);
      const probe = await probeMovement(target);
      t.evidence("movement while frozen", probe);
      t.check("the target cannot move before the reload", probe.moved <= HELD_WITHIN_BLOCKS, probe);
    });

    await t.step(`${MEMBER} reloads and rejoins the modl network`, async () => {
      const log = t.env.log(MEMBER);
      const since = await log.mark();
      await t.sendReload(MEMBER);
      await log.waitFor(ConsolePatterns.bukkitReloadComplete, { since, timeoutMs: 120_000, requireRunning: true });
      await log.waitFor(ConsolePatterns.memberBooted, { since, timeoutMs: 240_000, requireRunning: true, failOn: ConsolePatterns.pluginFailed });
      await log.waitFor(ConsolePatterns.memberNetworkConnected, { since, timeoutMs: 60_000, requireRunning: true });
    }, { timeoutMs: 420_000 });

    await t.step("the target is still frozen after the reload", async () => {
      const probe = await probeMovement(target);
      t.evidence("movement after the reload", probe);
      t.check("the target cannot move after the reload (position held within 0.2 blocks)", probe.moved <= HELD_WITHIN_BLOCKS, probe);
    }, { timeoutMs: 30_000 });

    await t.step("unfreezing releases the target again", async () => {
      const since = target.lastSeq;
      await t.consoleRun(ConsoleCommands.freeze(target.username), { server: t.coreServer, settleMs: 1500, timeoutMs: 10_000 });
      await target.awaitMessage(StaffMessages.unfrozen, 10_000, since);
      const probe = await probeMovement(target);
      t.evidence("movement after the unfreeze", probe);
      t.check("the target can move again", probe.moved > HELD_WITHIN_BLOCKS, probe);
    });
  },
});
