import { ConsoleCommands } from "../lib/contract";
import { defineScenario, type ScenarioContext } from "./framework";

const PAGE_FOOTER = /Page (\d+)\/(\d+)/i;
const NO_ALTS = /No linked accounts/i;

async function altPages(t: ScenarioContext, player: string): Promise<string[]> {
  const ending = new RegExp(`${PAGE_FOOTER.source}|${NO_ALTS.source}`, "i");
  const first = await t.consoleRun(ConsoleCommands.alts(player, 1), { expect: ending, timeoutMs: 15_000 });
  const total = Number(PAGE_FOOTER.exec(first.matched ?? "")?.[2] ?? 1);
  const lines = [...first.lines];
  for (let page = 2; page <= total; page += 1) {
    lines.push(...(await t.consoleRun(ConsoleCommands.alts(player, page), { expect: PAGE_FOOTER, timeoutMs: 15_000 })).lines);
  }
  return lines;
}

export const alts = defineScenario({
  name: "alts",
  description: "two accounts joining from the same IP are linked as alts and listed by /alts",
  topologies: ["standalone", "network", "remote"],
  async run(t: ScenarioContext) {
    const first = t.playerName("AltA");
    const second = t.playerName("AltB");
    await t.step("both accounts join from the same address", async () => {
      await t.bot(first);
      await t.bot(second);
    });

    await t.step("console /alts lists the second account for the first", async () => {
      let listed = false;
      for (let attempt = 0; attempt < 5 && !listed; attempt += 1) {
        await t.sleep(attempt === 0 ? 4000 : 3000);
        listed = (await altPages(t, first)).some((line) => line.toLowerCase().includes(second.toLowerCase()));
      }
      t.check(`${second} is listed as an alt of ${first}`, listed);
    });
  },
});
