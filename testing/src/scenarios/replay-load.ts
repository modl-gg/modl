import type { TestBot } from "../lib/bots/bot";
import { execInContainer } from "../lib/docker";
import { RECORD_EVERY_PLAYER } from "../lib/contract";
import { lineOf } from "../lib/lines";
import { defineScenario, type ScenarioContext } from "./framework";
import { captureReplayOf } from "./support";

const BOTS = 30;
const LOAD_ROUNDS = 14;
const ROUND_MS = 5000;
const SPREAD_BLOCKS = 600;
const MSPT_BUDGET_RATIO = 1.05;
const MSPT_NOISE_FLOOR_MS = 0.5;
const SURFACE_Y = -59;
const RSS_BUDGET_MEGABYTES = 64 * 1.1 + 48;
const TICK_MS = 50;

interface TickReading {
  msptAvg1m: number | null;
  msptMax1m: number | null;
  tps1m: number | null;
  rawMspt: string;
}

type Rcon = (command: string) => Promise<string>;

interface TickSampler {
  sampleRound(rcon: Rcon): Promise<void>;
  read(rcon: Rcon): Promise<TickReading>;
}

class PaperTickSampler implements TickSampler {
  async sampleRound(): Promise<void> {
    await Promise.resolve();
  }

  async read(rcon: Rcon): Promise<TickReading> {
    const mspt = await rcon("mspt");
    const tps = await rcon("tps");
    return { ...parseMspt(mspt), tps1m: firstNumber(tps), rawMspt: mspt };
  }
}

class FoliaTickSampler implements TickSampler {
  private readonly busyMs: number[] = [];
  private readonly lowestTps: number[] = [];
  private last = "";

  async sampleRound(rcon: Rcon): Promise<void> {
    const report = await rcon("tps");
    this.last = report;
    const utilisation = reportValue(report, /Utilisation:\s*([\d.]+)%/);
    const lowest = reportValue(report, /Lowest Region TPS:\s*([\d.]+)/);
    if (utilisation !== null) this.busyMs.push((utilisation / 100) * TICK_MS);
    if (lowest !== null) this.lowestTps.push(lowest);
  }

  async read(rcon: Rcon): Promise<TickReading> {
    await this.sampleRound(rcon);
    return {
      msptAvg1m: average(this.busyMs),
      msptMax1m: this.busyMs.length === 0 ? null : Math.max(...this.busyMs),
      tps1m: average(this.lowestTps),
      rawMspt: this.last,
    };
  }
}

function reportValue(report: string, pattern: RegExp): number | null {
  const match = pattern.exec(report.replace(/§./g, ""));
  return match?.[1] === undefined ? null : Number(match[1]);
}

function average(values: readonly number[]): number | null {
  return values.length === 0 ? null : values.reduce((sum, value) => sum + value, 0) / values.length;
}

function tickSampler(t: ScenarioContext): TickSampler {
  return lineOf(t.env.state.options).paper.regionized ? new FoliaTickSampler() : new PaperTickSampler();
}

interface LoadSample {
  label: string;
  msptAvg1m: number | null;
  msptMax1m: number | null;
  tps1m: number | null;
  rssMegabytes: number | null;
  rawMspt: string;
}

export const replayLoad = defineScenario({
  name: "replay-load",
  description: "30 players travel to fresh chunks with replays off and then with every player auto-recorded; MSPT stays within 5 percent",
  topologies: ["standalone", "remote"],
  lines: ["latest", "1.21", "folia"],
  async run(t: ScenarioContext) {
    const server = t.coreServer;
    const names = Array.from({ length: BOTS }, (_, index) => t.playerName(`L${String(index)}`));

    const off = await t.step("load with replays off", async () => {
      await configureReplays(t, server, false);
      return runLoad(t, server, names, "replays off");
    }, { timeoutMs: 600_000 });
    const on = await t.step("load with every player auto-recorded", async () => {
      await configureReplays(t, server, true);
      return runLoad(t, server, names, "auto-record on", true);
    }, { timeoutMs: 600_000 });

    await t.step("recording stays within the MSPT budget", () => {
      t.evidence("load samples", [off, on]);
      const before = off.msptAvg1m;
      const after = on.msptAvg1m;
      t.require(before !== null && after !== null, `MSPT could not be read (${off.rawMspt} / ${on.rawMspt})`);
      const budget = Math.max(before * MSPT_BUDGET_RATIO, before + MSPT_NOISE_FLOOR_MS);
      t.check(`average MSPT with recording (${after.toFixed(2)} ms) is within the budget (${budget.toFixed(2)} ms, off ${before.toFixed(2)} ms)`, after <= budget, { off, on });
      t.check("the server keeps 20 TPS while recording", (on.tps1m ?? 0) >= 19.5, on.tps1m);
      const rssDelta = (on.rssMegabytes ?? 0) - (off.rssMegabytes ?? 0);
      t.check(`resident memory grows by at most the replay budget (${String(rssDelta)} MB)`, rssDelta <= RSS_BUDGET_MEGABYTES, { off: off.rssMegabytes, on: on.rssMegabytes });
    });
  },
});

async function configureReplays(t: ScenarioContext, server: string, enabled: boolean): Promise<void> {
  t.quitAllBots();
  await t.instance.updateReplaySettings({ ...RECORD_EVERY_PLAYER, enabled });
  await t.restart(server);
}

async function runLoad(t: ScenarioContext, server: string, names: readonly string[], label: string, capture = false): Promise<LoadSample> {
  const bots: TestBot[] = [];
  for (const name of names) bots.push(await t.bot(name));
  const rcon = async (command: string): Promise<string> => t.env.console(server).rcon(command);
  const ticks = tickSampler(t);
  await t.sleep(10_000);
  for (let round = 0; round < LOAD_ROUNDS; round++) {
    const gather = round % 3 === 2;
    await Promise.all(bots.map(async (bot, index) => {
      const angle = (index / bots.length) * Math.PI * 2 + round;
      const distance = gather ? 4 : SPREAD_BLOCKS * (0.3 + 0.7 * ((round * 7 + index) % 10) / 10);
      const x = Math.round(Math.cos(angle) * distance);
      const z = Math.round(Math.sin(angle) * distance);
      await rcon(`tp ${bot.username} ${String(x)} ${String(SURFACE_Y)} ${String(z)}`);
    }));
    if (gather) await Promise.all(bots.slice(0, 10).map(async (bot) => bot.wander(2000)));
    await t.sleep(ROUND_MS);
    await ticks.sampleRound(rcon);
  }
  const sample: LoadSample = {
    label,
    ...(await ticks.read(rcon)),
    rssMegabytes: await javaRss(t, server),
  };
  t.evidence(label, sample);
  const first = names[0];
  if (capture && first !== undefined) {
    const captured = await captureReplayOf(t, first);
    t.evidence("replay captured under load", { bytes: captured.file.length, sections: captured.replay.sections.size, events: captured.replay.events.length });
  }
  t.quitAllBots();
  return sample;
}

function numbers(text: string): number[] {
  return [...text.replace(/§./g, "").matchAll(/\d+(?:\.\d+)?/g)].map((match) => Number(match[0]));
}

function parseMspt(text: string): { msptAvg1m: number | null; msptMax1m: number | null } {
  const values = numbers(text.split(/\r?\n/).slice(1).join(" "));
  if (values.length < 9) return { msptAvg1m: null, msptMax1m: null };
  return { msptAvg1m: values[6] ?? null, msptMax1m: values[8] ?? null };
}

function firstNumber(text: string): number | null {
  const values = numbers(text.replace(/^[^:]*:/, ""));
  return values[0] ?? null;
}

async function javaRss(t: ScenarioContext, server: string): Promise<number | null> {
  const script = "for p in /proc/[0-9]*; do if tr '\\0' ' ' < $p/cmdline 2>/dev/null | grep -q java; then grep VmRSS $p/status; fi; done";
  const result = await execInContainer(t.env.server(server).container, ["/bin/sh", "-c", script]);
  const kilobytes = numbers(result.stdout)[0];
  return kilobytes === undefined ? null : Math.round(kilobytes / 1024);
}
