import { ConsoleCommands, punishmentIssued } from "../lib/contract";
import { defineScenario, type ScenarioContext } from "./framework";
import { awaitMembersOnHub, expectJoinRefused, memberNames, ownerSession } from "./support";

interface Seed {
  bannedPlayer: string;
  ownerEmail: string;
  minecraftUuid: string;
}

async function seedData(t: ScenarioContext, prefix: string): Promise<Seed> {
  const session = await ownerSession(t);
  const bannedPlayer = t.playerName(prefix);
  await t.bot(bannedPlayer);
  t.quitBot(bannedPlayer);
  await t.consoleExpect(ConsoleCommands.ban(bannedPlayer, "harness persistence"), punishmentIssued("ban", bannedPlayer));
  await t.sleep(3000);
  return { bannedPlayer, ownerEmail: session.profile.email, minecraftUuid: session.profile.minecraftUuid };
}

async function verifyData(t: ScenarioContext, seed: Seed): Promise<void> {
  t.check("the instance is still claimed", await t.instance.isClaimed());
  const profile = await t.instance.profile();
  t.check("the panel session survived", profile?.email === seed.ownerEmail, profile);
  t.check("the linked Minecraft account survived", (profile?.minecraftUuid ?? "") === seed.minecraftUuid, { before: seed.minecraftUuid, after: profile?.minecraftUuid });
  const refusal = await expectJoinRefused(t, seed.bannedPlayer);
  t.expectMatch("the ban is still enforced", refusal, /ban/i);
  const fresh = await t.bot(t.playerName("Back"));
  t.check("a new player can join", fresh.connected);
}

async function awaitNetworkRecovered(t: ScenarioContext, server: string): Promise<void> {
  if (t.env.server(server).role !== "proxy") return;
  const linked = await awaitMembersOnHub(t, memberNames(t));
  t.evidence("servers behind the proxy linked again", linked);
}

export const restartPersistence = defineScenario({
  name: "restart-persistence",
  description: "a graceful restart of the server holding the data keeps the claim, sessions, links and punishments",
  topologies: ["standalone", "network", "remote"],
  async run(t: ScenarioContext) {
    const server = t.env.state.dataServer;
    const seed = await t.step("seed data", async () => seedData(t, "Keep"));
    await t.step(
      `graceful restart of ${server}`,
      async () => {
        const report = await t.restart(server);
        t.evidence("restart", report);
        t.check("the server stopped cleanly", report.stop?.graceful === true, report.stop);
        await awaitNetworkRecovered(t, server);
      },
      { timeoutMs: 420_000, allowConsoleErrors: true },
    );
    await t.step("data survived the restart", async () => verifyData(t, seed), { timeoutMs: 180_000 });
  },
});

export const crashRecovery = defineScenario({
  name: "crash-recovery",
  description: "a SIGKILL of the server holding the data recovers on the next start with data intact",
  topologies: ["standalone", "network", "remote"],
  async run(t: ScenarioContext) {
    const server = t.env.state.dataServer;
    const seed = await t.step("seed data", async () => seedData(t, "Crash"));
    await t.step(
      `docker kill ${server} and start it again`,
      async () => {
        const killed = await t.kill(server);
        t.evidence("killed", killed);
        t.check("the container died from SIGKILL", killed.exitCode === 137, killed);
        const started = await t.start(server);
        t.evidence("started", started);
        await awaitNetworkRecovered(t, server);
      },
      { timeoutMs: 420_000, allowConsoleErrors: true },
    );
    await t.step("data survived the crash", async () => verifyData(t, seed), { timeoutMs: 180_000 });
  },
});
