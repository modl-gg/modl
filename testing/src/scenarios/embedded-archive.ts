import { copyFileSync, existsSync, readFileSync, rmSync, statSync } from "node:fs";
import { join } from "node:path";
import { ConsolePatterns, StartupFailurePatterns } from "../lib/contract";
import type { LogMark } from "../lib/logs";
import { embeddedArchiveName, productVersion } from "../lib/product";
import { replaceSharedFile, writeSharedFile } from "../lib/shared-files";
import { RELEASE_MIRROR_URL, ReleaseMirror } from "../lib/topologies/release-mirror";
import { embeddedArchiveFile, embeddedServerDir, patchConnection } from "../lib/topologies/server-files";
import { defineScenario, type ScenarioContext } from "./framework";
import { expectJoinRefused } from "./support";
import { needsUpdateFixture } from "./update-support";

const RESTART_TIMEOUT_MS = 600_000;
const NOTICE_TIMEOUT_MS = 120_000;
const UNREACHABLE_MIRROR = "http://127.0.0.1:9/releases";
const STEP_OPTIONS = { timeoutMs: RESTART_TIMEOUT_MS, allowConsoleErrors: true } as const;

async function restartFromMark(t: ScenarioContext, server: string): Promise<LogMark> {
  const since = await t.env.log(server).mark();
  t.evidence(`${server} restarted`, await t.restart(server));
  return since;
}

async function downloadLines(t: ScenarioContext, server: string, since: LogMark): Promise<string[]> {
  return (await t.env.log(server).find(ConsolePatterns.panelArchiveDownload, since)).map((line) => line.text);
}

async function playerJoins(t: ScenarioContext, prefix: string): Promise<void> {
  const name = t.playerName(prefix);
  const bot = await t.bot(name);
  t.check(`${name} joins`, bot.connected);
  t.quitBot(name);
}

async function corruptedCopy(archive: string, backup: string): Promise<void> {
  copyFileSync(archive, backup);
  const bytes = readFileSync(archive);
  const middle = Math.floor(bytes.length / 2);
  bytes.writeUInt8(bytes.readUInt8(middle) ^ 0xff, middle);
  await writeSharedFile(archive, bytes);
}

export const embeddedDropIn = defineScenario({
  name: "embedded-drop-in",
  description: "an archive in plugins/modl/server/ starts the built-in panel with no download, even when every release source is unreachable",
  topologies: ["standalone"],
  async run(t: ScenarioContext) {
    const server = t.coreServer;
    const dir = t.env.server(server).dataDir;
    const archive = embeddedArchiveFile(dir);
    try {
      await t.step(
        "the panel starts from the dropped-in archive with an unreachable mirror configured",
        async () => {
          t.require(existsSync(archive), `${archive} is missing; the topology installs it, or the first start downloads it`);
          patchConnection(dir, { "embedded.download-url": UNREACHABLE_MIRROR });
          const since = await restartFromMark(t, server);
          const downloads = await downloadLines(t, server, since);
          t.check("nothing was downloaded", downloads.length === 0, downloads);
          t.check("the archive is still in place", existsSync(archive), archive);
          await playerJoins(t, "DropIn");
        },
        { timeoutMs: RESTART_TIMEOUT_MS },
      );
    } finally {
      await t.step(
        "the download address is reset",
        async () => {
          patchConnection(dir, { "embedded.download-url": "" });
          t.evidence("restart", await t.restart(server));
        },
        STEP_OPTIONS,
      );
    }
  },
});

export const embeddedTamperedArchive = defineScenario({
  name: "embedded-tampered-archive",
  description: "an archive in plugins/modl/server/ that does not match the plugin jar's pin is refused: modl fails closed and names the file, and recovers once the right file is back",
  topologies: ["standalone"],
  async run(t: ScenarioContext) {
    const server = t.coreServer;
    const dir = t.env.server(server).dataDir;
    const archive = embeddedArchiveFile(dir);
    const backup = join(embeddedServerDir(dir), `${embeddedArchiveName(productVersion())}.good`);
    try {
      await t.step(
        "a same-size corrupted archive is refused",
        async () => {
          t.require(existsSync(archive), `${archive} is missing`);
          const size = statSync(archive).size;
          t.quitAllBots();
          await t.env.stopGracefully(server);
          await corruptedCopy(archive, backup);
          t.check("the corrupted copy keeps the size", statSync(archive).size === size);
          const since = await t.env.log(server).mark();
          await t.env.startExisting(server);
          await t.env.waitForMinecraft(server, since, "none");
          const mismatch = await t.env.log(server).waitFor(ConsolePatterns.panelArchiveMismatch, { since, timeoutMs: NOTICE_TIMEOUT_MS });
          t.evidence("mismatch", mismatch.text);
          t.expectMatch("the reason names the archive", mismatch.text, new RegExp(embeddedArchiveName(productVersion()).replace(/\./g, "\\.")));
          await t.env.log(server).waitFor(StartupFailurePatterns.reason, { since, timeoutMs: NOTICE_TIMEOUT_MS });
          await t.env.log(server).waitFor(StartupFailurePatterns.consequence, { since, timeoutMs: NOTICE_TIMEOUT_MS });
          const booted = await t.env.log(server).find(ConsolePatterns.pluginBooted, since);
          t.check("modl did not boot", booted.length === 0, booted.map((line) => line.text));
          await expectJoinRefused(t, t.playerName("Tamper"), 30_000);
        },
        STEP_OPTIONS,
      );
    } finally {
      await t.step(
        "the right archive is put back and modl starts",
        async () => {
          await t.env.stopGracefully(server);
          if (existsSync(backup)) {
            await replaceSharedFile(backup, archive);
            rmSync(backup, { force: true });
          }
          t.evidence("start", await t.start(server));
          await playerJoins(t, "Recovered");
        },
        STEP_OPTIONS,
      );
    }
  },
});

export const embeddedDownload = defineScenario({
  name: "embedded-download",
  description: "without an archive in plugins/modl/server/ the plugin downloads its pinned release (archive and signed release file) from the release mirror and starts",
  topologies: ["standalone"],
  unsupportedBy: needsUpdateFixture,
  async run(t: ScenarioContext) {
    const server = t.coreServer;
    const dir = t.env.server(server).dataDir;
    const archive = embeddedArchiveFile(dir);
    const file = embeddedArchiveName(productVersion());
    const mirror = new ReleaseMirror(t.env.state.project);
    await t.step(
      "the plugin downloads the archive from the mirror on its next start",
      async () => {
        t.quitAllBots();
        await t.env.stopGracefully(server);
        rmSync(archive, { force: true });
        await mirror.publishFiles("base");
        const requestsSince = await mirror.mark();
        const since = await t.env.log(server).mark();
        await t.env.startExisting(server);
        await t.env.waitForMinecraft(server, since, "core");
        await t.env.waitForPanel();
        const downloads = await downloadLines(t, server, since);
        t.check("the download line names the archive and the mirror", downloads.some((line) => line.includes(file) && line.includes(RELEASE_MIRROR_URL)), downloads);
        const requests = await mirror.requests(requestsSince);
        t.check("the mirror served the archive", requests.some((line) => line.includes(`/${file}`)), requests);
        t.check("the mirror served the signed release file", requests.some((line) => line.includes("/modl-release.json")), requests);
        t.check("the archive was kept for later starts", existsSync(archive), archive);
        await playerJoins(t, "Download");
      },
      { timeoutMs: RESTART_TIMEOUT_MS },
    );
  },
});
