import { ConsoleCommands, PARDON_SUCCESS, PlayerNotices, punishmentIssued } from "../lib/contract";
import type { TestBot } from "../lib/bots/bot";
import { defineScenario, type ScenarioContext } from "./framework";

async function observerSees(t: ScenarioContext, speaker: TestBot, observer: TestBot, marker: string): Promise<boolean> {
  const since = observer.lastSeq;
  speaker.chat(marker);
  await t.sleep(3000);
  return observer.messagesSince(since).some((message) => message.text.includes(marker));
}

export const muteChat = defineScenario({
  name: "mute-chat",
  description: "a console mute blocks the target's chat in realtime; unmute restores it",
  topologies: ["standalone", "network", "remote"],
  async run(t: ScenarioContext) {
    const targetName = t.playerName("Mute");
    const target = await t.step("the target joins", async () => t.bot(targetName));
    const observer = await t.step("an observer joins", async () => t.bot(t.playerName("Obs")));

    await t.step("chat works before the mute", async () => {
      t.check("observer sees the target's chat", await observerSees(t, target, observer, `before-${Date.now().toString(36)}`));
    });

    await t.step("a console mute is applied and the online target is told at once", async () => {
      const targetSince = target.lastSeq;
      await t.consoleExpect(ConsoleCommands.mute(targetName, "harness e2e mute"), punishmentIssued("mute", targetName));
      const notice = await target.awaitMessage(PlayerNotices.muted, 10_000, targetSince).catch(() => null);
      t.evidence("target chat", target.messagesSince(targetSince).map((message) => message.text));
      t.check("the target gets the mute notice without chatting", notice !== null);
    });

    await t.step("the muted player's chat is blocked", async () => {
      const targetSince = target.lastSeq;
      const marker = `muted-${Date.now().toString(36)}`;
      t.check("observer does not receive the muted message", !(await observerSees(t, target, observer, marker)), { marker });
      const notices = target.messagesSince(targetSince).map((message) => message.text);
      t.expectMatch("the target is told they are muted", notices, /mute/i);
    });

    await t.step("unmute restores chat", async () => {
      await t.consoleExpect(ConsoleCommands.unmute(targetName), PARDON_SUCCESS);
      await t.sleep(2000);
      t.check("observer sees the target's chat again", await observerSees(t, target, observer, `after-${Date.now().toString(36)}`));
    });
  },
});
