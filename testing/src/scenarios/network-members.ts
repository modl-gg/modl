import { ConsoleCommands, ConsolePatterns, Endpoints, HotbarItems, ImportMessages, PlayerCommands, ProxyCommandMessages, punishmentIssued, SETUP_REPLAY_CHOICE, StaffMessages } from "../lib/contract";
import { z } from "zod";
import { HttpStatus } from "../lib/http";
import type { TestBot } from "../lib/bots/bot";
import { listServerKeys, ProxiedServerStates } from "../lib/server-keys";
import { CONTAINER_PORTS } from "../lib/topologies/compose";
import { BEHIND_PROXY_MODE } from "../lib/topologies/modl-config";
import { offlineUuid } from "../lib/util";
import { defineScenario, type ScenarioContext } from "./framework";
import { awaitHotbarSlot, awaitMembersOnHub, awaitProxiedServer, awaitVisibility, captureReplay, linkedOwnerBot, memberNames, proxyLocation, recordEveryPlayer } from "./support";

const STAFF_MODE_ENABLED = StaffMessages.staffModeEnabled;
const STAFF_MODE_DISABLED = StaffMessages.staffModeDisabled;
const TARGETING = StaffMessages.targeting;
const FROZEN = StaffMessages.frozen;
const UNFROZEN = StaffMessages.unfrozen;
const FREEZE_DEADLINE_MS = 5000;
const STAT_WIPE_BAN_SECONDS = 15;
const STAT_WIPE_BAN = `${String(STAT_WIPE_BAN_SECONDS)}s`;
const BAN_EXPIRY_MARGIN_MS = 5000;
const STAT_WIPE_TIMEOUT_MS = 180_000;
const STAT_WIPE_ACK_TIMEOUT_MS = 60_000;

async function staffListShowsVanished(t: ScenarioContext, player: string): Promise<boolean | null> {
  const result = await t.consoleRun(ConsoleCommands.staffList, { server: t.coreServer, settleMs: 1200, timeoutMs: 6000 });
  const entry = result.lines.find((line) => line.includes(player));
  return entry === undefined ? null : StaffMessages.staffListVanishTag.test(entry);
}

async function awaitStaffListVanished(t: ScenarioContext, player: string, vanished: boolean, timeoutMs = 10_000): Promise<boolean | null> {
  const deadline = Date.now() + timeoutMs;
  let shown = await staffListShowsVanished(t, player);
  while (shown !== vanished && Date.now() < deadline) shown = await staffListShowsVanished(t, player);
  return shown;
}

async function moveTo(t: ScenarioContext, bot: TestBot, server: string): Promise<void> {
  const switches = bot.serverSwitches;
  bot.chat(PlayerCommands.switchServer(server));
  await bot.awaitServerSwitch(switches);
  t.check(`the proxy places ${bot.username} on ${server}`, (await proxyLocation(t, bot.username)) === server);
}

const playerPunishments = z.object({
  profile: z.object({
    punishments: z.array(z.object({ id: z.string(), data: z.object({ statWipeCompleted: z.boolean().optional() }).nullish() })),
  }),
});

async function statWipeCompleted(t: ScenarioContext, player: string, punishmentId: string): Promise<boolean> {
  const result = await t.instance.apiWithCredentials(Endpoints.minecraftPlayer(offlineUuid(player)), { auth: "api-key" });
  if (result.status !== HttpStatus.ok) return false;
  const punishment = playerPunishments.parse(result.json).profile.punishments.find((entry) => entry.id === punishmentId);
  return punishment?.data?.statWipeCompleted === true;
}

async function awaitStatWipeCompleted(t: ScenarioContext, player: string, punishmentId: string): Promise<boolean> {
  const deadline = Date.now() + STAT_WIPE_ACK_TIMEOUT_MS;
  while (Date.now() < deadline) {
    if (await statWipeCompleted(t, player, punishmentId)) return true;
    await t.sleep(2000);
  }
  return false;
}

async function freezeLatencyMs(owner: TestBot, target: TestBot, expect: RegExp): Promise<number> {
  const since = target.lastSeq;
  const sentAt = Date.now();
  owner.chat(PlayerCommands.freeze(target.username));
  await target.awaitMessage(expect, 10_000, since);
  return Date.now() - sentAt;
}

export const networkMembers = defineScenario({
  name: "network-members",
  description: "behind-proxy auto-detection, server keys on the network hub, cross-server staff target, hotbar vanish as a staff action, freeze latency, a replay captured on a member and uploaded directly, and a stat wipe run and acknowledged by the members",
  topologies: ["network", "network-bungee"],
  async run(t: ScenarioContext) {
    const members = memberNames(t);

    await t.step("behind-proxy mode was detected from proxy forwarding on first start", () => {
      const detections = t.env.state.memberDetection;
      t.evidence("detections", detections);
      for (const member of members) {
        t.check(`${member} runs behind the proxy`, detections.find((entry) => entry.server === member)?.writtenMode === BEHIND_PROXY_MODE, detections);
      }
      const firstStarts = detections.filter((entry) => entry.firstStart);
      if (firstStarts.length === 0) t.note("auto-detection was not exercised: the members already had connection.yml (use up --fresh)");
      for (const entry of firstStarts) t.check(`${entry.server} wrote mode: behind-proxy by itself`, entry.autoDetected, entry);
    });

    await t.step("every member is linked to the network hub with its own server key", async () => {
      const linked = await awaitMembersOnHub(t, members);
      t.evidence("members on the hub", linked);
      for (const member of members) {
        t.check(`${member} is listed as a member with its key`, linked.some((entry) => entry.keyName === member && entry.hubConnected), linked);
        const connected = await t.env.log(member).find(ConsolePatterns.memberNetworkConnected);
        t.check(`${member} printed that it joined the modl network`, connected.length > 0, connected.map((line) => line.text));
      }
      const unknownNames = await t.env.log(t.coreServer).find(ConsolePatterns.networkUnknownServerName);
      t.check("every member uses a server name of the proxy config", unknownNames.length === 0, unknownNames.map((line) => line.text));
    });

    await t.step("the panel lists every server of the proxy config with its key as connected", async () => {
      const keys = await listServerKeys(t.instance);
      for (const member of members) {
        const listed = await awaitProxiedServer(t, member, ProxiedServerStates.connected);
        t.evidence(`${member} as listed by the proxy`, listed);
        t.check(`${member} is listed with its own key`, listed.keyId !== null && keys.some((key) => key.id === listed.keyId && key.name === member), listed);
        t.check(`${member} is listed with its address in the proxy config`, listed.address === `${member}:${CONTAINER_PORTS.game}`, listed);
      }
    });

    await t.step("/proxycmd from a member is refused while its key does not allow it", async () => {
      const member = members[0];
      t.require(member, "the network has no member");
      const proxyLog = t.env.log(t.coreServer);
      const since = await proxyLog.mark();
      await t.consoleExpect(ConsoleCommands.proxyImport, ProxyCommandMessages.disabled, { server: member, timeoutMs: 15_000 });
      const printed = await proxyLog.text(since);
      t.check("the proxy ran nothing for the member", !printed.some((line) => ImportMessages.localConsoleOnly.test(line)
        || ImportMessages.previewReady.test(line) || ImportMessages.liteBansMissing.test(line)), printed);
    });

    const owner = await t.step("the linked owner joins through the proxy", async () => linkedOwnerBot(t), { timeoutMs: 180_000 });
    const target = await t.step("a target joins through the proxy", async () => t.bot(t.playerName("Net")));

    await t.step("the target moves to survival", async () => {
      await moveTo(t, target, "survival");
      t.check("the owner is still on lobby", (await proxyLocation(t, owner.username)) === "lobby");
    });

    await t.step("staff mode turns on", async () => {
      const replies = await owner.runCommand(PlayerCommands.staffMode, { expect: STAFF_MODE_ENABLED, timeoutMs: 8000 });
      t.expectMatch("staff mode enabled", replies.map((message) => message.text), STAFF_MODE_ENABLED);
    });

    await t.step("/target follows the target to the other server", async () => {
      const switches = owner.serverSwitches;
      const since = owner.lastSeq;
      owner.chat(PlayerCommands.target(target.username));
      await owner.awaitServerSwitch(switches, 25_000);
      t.check("the owner is moved to survival", (await proxyLocation(t, owner.username)) === "survival");
      const replies = owner.messagesSince(since).map((message) => message.text);
      t.evidence("target replies", replies);
      t.expectMatch("the target command acknowledged", replies, TARGETING);
    });

    await t.step("the hotbar vanish toggle on survival travels to the proxy as a staff action", async () => {
      const vanish = await awaitHotbarSlot(t, owner, HotbarItems.vanish);
      const before = await staffListShowsVanished(t, owner.username);
      t.require(before !== null, `/stafflist on the proxy does not list ${owner.username}`);
      const since = owner.lastSeq;
      await owner.useHotbarSlot(vanish.slot);
      await owner.awaitMessage(StaffMessages.vanishToggled, 10_000, since);
      const after = await awaitStaffListVanished(t, owner.username, !before);
      const visibility = await awaitVisibility(target, owner.username, before);
      t.evidence("vanish toggle", { before, after, targetSeesOwner: target.canSee(owner.username) });
      t.check("the proxy's /stafflist reflects the hotbar toggle", after === !before, { before, after });
      t.check("the target's view of the owner follows the toggle", visibility, { vanishedBefore: before });

      const restoreSince = owner.lastSeq;
      await owner.useHotbarSlot((await awaitHotbarSlot(t, owner, HotbarItems.vanish)).slot);
      await owner.awaitMessage(StaffMessages.vanishToggled, 10_000, restoreSince);
      t.check("toggling again restores the previous vanish state", (await awaitStaffListVanished(t, owner.username, before)) === before);
      const refused = await t.env.log(t.coreServer).find(ConsolePatterns.networkRefusedStaffAction);
      t.check("the proxy accepted the hotbar actions from survival", refused.length === 0, refused.map((line) => line.text));
    });

    await t.step("freeze reaches the target on survival within 5 seconds, and unfreeze releases it", async () => {
      const frozenMs = await freezeLatencyMs(owner, target, FROZEN);
      const releasedMs = await freezeLatencyMs(owner, target, UNFROZEN);
      t.evidence("freeze latency", { frozenMs, releasedMs });
      t.check(`the freeze reached the target within ${FREEZE_DEADLINE_MS} ms`, frozenMs <= FREEZE_DEADLINE_MS, frozenMs);
      t.check(`the unfreeze reached the target within ${FREEZE_DEADLINE_MS} ms`, releasedMs <= FREEZE_DEADLINE_MS, releasedMs);
    });

    await t.step("a replay recorded on survival is captured through the hub and uploaded by survival itself", async () => {
      await recordEveryPlayer(t, "survival");
      try {
        await captureReplay(t, target);
      } finally {
        await t.instance.updateReplaySettings(SETUP_REPLAY_CHOICE);
      }
    }, { timeoutMs: 180_000 });

    await t.step("staff mode turns off", async () => {
      const replies = await owner.runCommand(PlayerCommands.staffMode, { expect: STAFF_MODE_DISABLED, timeoutMs: 8000 });
      t.expectMatch("staff mode disabled", replies.map((message) => message.text), STAFF_MODE_DISABLED);
    });

    await t.step(
      "a stat wipe after a ban runs on the members and is acknowledged",
      async () => {
        const player = target.username;
        await t.consoleExpect(ConsoleCommands.statWipeBan(player, STAT_WIPE_BAN, "harness stat wipe"), punishmentIssued("ban", player));
        const banEnds = Date.now() + STAT_WIPE_BAN_SECONDS * 1000 + BAN_EXPIRY_MARGIN_MS;
        t.quitBot(player);
        const marks = await Promise.all(members.map(async (member) => ({ member, since: await t.env.log(member).mark() })));
        await t.sleep(Math.max(0, banEnds - Date.now()));
        const relog = await t.bot(player).then(() => null, (error: unknown) => (error instanceof Error ? error.message : String(error)));
        t.evidence("first join after the ban ended", relog ?? "joined");
        t.check("the first join after the ban is refused with the stat-wipe re-log message",
          relog !== null && ConsolePatterns.statWipeRelog.test(relog), relog);
        const executed = await Promise.any(marks.map(async ({ member, since }) => ({
          member,
          line: await t.env.log(member).waitFor(ConsolePatterns.statWipeExecuted, { since, timeoutMs: STAT_WIPE_TIMEOUT_MS }),
        })));
        t.evidence("stat wipe executed", { member: executed.member, line: executed.line.text });
        const execution = ConsolePatterns.statWipeExecuted.exec(executed.line.text);
        t.check("the configured stat-wipe command ran for the player", execution?.[2] === player, executed.line.text);
        const punishmentId = execution?.[3];
        t.require(punishmentId !== undefined, `the stat-wipe line names no punishment: ${executed.line.text}`);
        t.check("the punishment records the completed stat wipe", await awaitStatWipeCompleted(t, player, punishmentId));
        const rejoined = await t.bot(player);
        t.check(`${player} joins again once the stat wipe is acknowledged`, rejoined.connected);
      },
      { timeoutMs: STAT_WIPE_BAN_SECONDS * 1000 + BAN_EXPIRY_MARGIN_MS + STAT_WIPE_TIMEOUT_MS + STAT_WIPE_ACK_TIMEOUT_MS + 30_000 },
    );
  },
});
