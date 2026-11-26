import { ConsoleCommands, Endpoints, punishmentIssued } from "../lib/contract";
import { offlineUuid } from "../lib/util";
import { defineScenario, type ScenarioContext } from "./framework";
import { expectJoinRefused, ownerSession } from "./support";

function onlineFlag(body: unknown): boolean | null {
  if (body === null || typeof body !== "object") return null;
  for (const [key, value] of Object.entries(body)) {
    if (key === "isOnline" && typeof value === "boolean") return value;
    const nested = onlineFlag(value);
    if (nested !== null) return nested;
  }
  return null;
}

export const banKick = defineScenario({
  name: "ban-kick",
  description: "a console ban kicks the online player immediately over realtime and denies the rejoin",
  topologies: ["standalone", "network", "remote"],
  async run(t: ScenarioContext) {
    const target = t.playerName("Ban");
    const bot = await t.step("the target joins", async () => t.bot(target));

    await t.step("a console ban kicks the online target in realtime", async () => {
      const disconnected = bot.awaitDisconnect(30_000);
      const started = Date.now();
      await t.consoleExpect(ConsoleCommands.ban(target, "harness e2e ban"), punishmentIssued("ban", target));
      const reason = await disconnected;
      t.evidence("kick", { reason, latencyMs: Date.now() - started });
      t.expectMatch("the kick message mentions the ban", reason, /ban/i);
    });

    await t.step("the banned player is denied on rejoin", async () => {
      const reason = await expectJoinRefused(t, target);
      t.evidence("join refused", reason);
      t.expectMatch("the denial mentions the ban", reason, /ban/i);
    });

    await t.step("the panel does not show the refused player as online", async () => {
      await ownerSession(t);
      const result = await t.instance.api(Endpoints.panelPlayer(offlineUuid(target)));
      const online = onlineFlag(result.json);
      t.evidence("player", { status: result.status, isOnline: online });
      t.check("the player is offline after the refused rejoin", result.status === 200 && online === false);
    });
  },
});
