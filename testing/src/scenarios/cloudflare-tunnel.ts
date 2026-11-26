import { createHash } from "node:crypto";
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { readYamlValue } from "../lib/config-files";
import { auditConsole } from "../lib/console-policy";
import { ConsolePatterns } from "../lib/contract";
import { runDocker } from "../lib/docker";
import { REPO_ROOT } from "../lib/paths";
import { connectionFile, embeddedServerDir, modlDir, patchConnection } from "../lib/topologies/server-files";
import { defineScenario, type ScenarioContext } from "./framework";
import { serverReloads } from "./support";

const PINNED_RELEASE = join(REPO_ROOT, "backend/src/main/resources/gg/modl/backend/tunnel/cloudflared/cloudflared-release.txt");
const TUNNEL_URL = "https://modl-tunnel-check.example.com";
const FAKE_TOKEN = Buffer.from(JSON.stringify({
  a: "0123456789abcdef0123456789abcdef",
  t: "6f1c2a4e-8b3d-4c5e-9f70-1a2b3c4d5e6f",
  s: Buffer.from("not-a-real-tunnel-secret-0123456789").toString("base64"),
})).toString("base64");
const TUNNEL_KEYS = ["embedded.tunnel.provider", "embedded.tunnel.token", "embedded.public-url"] as const;
const PANEL_CONTAINER_PORT = 8080;
const BOOT_TIMEOUT_MS = 600_000;
const NOTICE_TIMEOUT_MS = 180_000;
const STEP_OPTIONS = { timeoutMs: BOOT_TIMEOUT_MS } as const;

interface PinnedBuild {
  file: string;
  size: number;
  sha256: string;
}

function pinnedBuilds(): PinnedBuild[] {
  const [header, ...lines] = readFileSync(PINNED_RELEASE, "utf8").trim().split("\n");
  const version = header?.replace("cloudflared ", "") ?? "";
  if (version === "") throw new Error(`${PINNED_RELEASE} has no version`);
  return lines.map((line) => {
    const [os, arch, size, sha256] = line.trim().split(/ +/);
    if (os === undefined || arch === undefined || size === undefined || sha256 === undefined) throw new Error(`malformed pin '${line}'`);
    return { file: `cloudflared-${os}-${arch}${os === "windows" ? ".exe" : ""}`, size: Number(size), sha256 };
  });
}

function tunnelDir(serverDir: string): string {
  return join(modlDir(serverDir), "data", "tunnel");
}

function fileSha256(file: string): string {
  return createHash("sha256").update(readFileSync(file)).digest("hex");
}

async function containerShell(container: string, script: string): Promise<{ code: number; stdout: string }> {
  const result = await runDocker(["exec", container, "bash", "-c", script], { allowFailure: true, timeoutMs: 30_000 });
  return { code: result.code, stdout: result.stdout.trim() };
}

async function canConnect(container: string, host: string, port: number): Promise<boolean> {
  return (await containerShell(container, `timeout 5 bash -c 'exec 3<>/dev/tcp/${host}/${String(port)}'`)).code === 0;
}

async function cloudflaredPids(container: string): Promise<number[]> {
  const listing = await containerShell(container,
    "for p in /proc/[0-9]*; do case \"$(readlink \"$p/exe\" 2>/dev/null)\" in */tunnel/cloudflared-*) echo \"${p#/proc/}\";; esac; done");
  return listing.stdout.split("\n").filter((line) => line !== "").map(Number);
}

async function startWithTunnel(t: ScenarioContext, server: string): Promise<void> {
  t.quitAllBots();
  await t.env.stopGracefully(server);
  patchConnection(t.env.server(server).dataDir, {
    "embedded.tunnel.provider": "cloudflare",
    "embedded.tunnel.token": FAKE_TOKEN,
    "embedded.public-url": TUNNEL_URL,
  });
  const since = await t.env.log(server).mark();
  await t.env.startExisting(server);
  await t.env.waitForMinecraft(server, since, "core");
}

export const cloudflareTunnel = defineScenario({
  name: "cloudflare-tunnel",
  description: "embedded.tunnel with a well-formed but unknown token: the pinned cloudflared is downloaded and verified, the panel listens on 127.0.0.1 only, the not-connected notice appears with no other unexpected line, and /reload leaves no cloudflared behind",
  topologies: ["standalone"],
  async run(t: ScenarioContext) {
    const server = t.coreServer;
    const dir = t.env.server(server).dataDir;
    const container = t.env.server(server).container;
    const original = Object.fromEntries(TUNNEL_KEYS.map((key) => [key, readYamlValue(connectionFile(dir), key) ?? ""]));
    try {
      const since = await t.step("modl starts with the tunnel configured", async () => {
        const mark = await t.env.log(server).mark();
        await startWithTunnel(t, server);
        return mark;
      }, STEP_OPTIONS);

      await t.step("the pinned cloudflared is downloaded and verified", async () => {
        const notice = await t.env.log(server).waitFor(
          new RegExp(`${ConsolePatterns.tunnelNotConnected.source}|${ConsolePatterns.tunnelCannotStart.source}`),
          { since, timeoutMs: NOTICE_TIMEOUT_MS, requireRunning: true },
        );
        t.evidence("tunnel notice", notice.text);
        const files = existsSync(tunnelDir(dir)) ? readdirSync(tunnelDir(dir)) : [];
        t.evidence("tunnel folder", files);
        const pins = pinnedBuilds();
        const binary = files.map((file) => pins.find((pin) => pin.file === file)).find((pin) => pin !== undefined);
        t.require(binary !== undefined, `no pinned cloudflared build in ${tunnelDir(dir)}`);
        t.check("the binary has the pinned SHA-256", fileSha256(join(tunnelDir(dir), binary.file)) === binary.sha256);
        t.check("nothing but the binary, its config and its pid file is kept",
          files.every((file) => [binary.file, "config.yml", "cloudflared.pid"].includes(file)), files);
        const serverLog = readFileSync(join(embeddedServerDir(dir), "logs", "server.log"), "utf8");
        t.check("the server log records the install", /Installed cloudflared \S+ at \S+/.test(serverLog)
          || /Starting Cloudflare Tunnel for \S+ with cloudflared \S+/.test(serverLog));
        t.check("the token is not in the server log", !serverLog.includes(FAKE_TOKEN));
        t.check("the not-connected notice names the token and port 7844", ConsolePatterns.tunnelNotConnected.test(notice.text), notice.text);
      }, { timeoutMs: NOTICE_TIMEOUT_MS + 30_000 });

      await t.step("the panel listens on 127.0.0.1 only", async () => {
        const address = (await containerShell(container, "getent hosts \"$(cat /etc/hostname)\" | awk '{print $1}' | head -n 1")).stdout;
        t.evidence("container address", address);
        t.check("127.0.0.1 answers", await canConnect(container, "127.0.0.1", PANEL_CONTAINER_PORT));
        t.check(`${address} refuses`, address !== "" && !(await canConnect(container, address, PANEL_CONTAINER_PORT)));
      });

      await t.step("the console shows only expected modl lines", async () => {
        const audit = auditConsole(server, await t.env.entries(server, since), await t.env.leakIndex());
        t.evidence("modl lines", audit.expectedModl.map((finding) => `[${finding.rule ?? ""}] ${finding.text}`));
        t.check("only expected modl lines", audit.unexpectedModl.length === 0, audit.unexpectedModl);
        t.check("the token never reaches the console", audit.expectedModl.every((finding) => !finding.text.includes(FAKE_TOKEN)));
      });

      if (serverReloads(t)) await t.step("/reload stops cloudflared and starts one new process", async () => {
        const before = await cloudflaredPids(container);
        t.evidence("cloudflared before", before);
        t.require(before.length === 1, `expected one cloudflared process, found ${String(before.length)}`);
        const mark = await t.env.log(server).mark();
        await t.sendReload(server);
        await t.env.log(server).waitFor(ConsolePatterns.bukkitReloadComplete, { since: mark, timeoutMs: 120_000, requireRunning: true });
        await t.env.log(server).waitFor(ConsolePatterns.pluginBooted, {
          since: mark, timeoutMs: 240_000, requireRunning: true, failOn: ConsolePatterns.pluginFailed,
        });
        await t.sleep(10_000);
        const after = await cloudflaredPids(container);
        t.evidence("cloudflared after", after);
        t.check("the earlier cloudflared is gone", before.every((pid) => !after.includes(pid)), { before, after });
        t.check("at most one cloudflared runs", after.length <= 1, after);
        const cannotStart = await t.env.log(server).find(ConsolePatterns.tunnelCannotStart, since);
        t.check("stopping and restarting the tunnel printed no cannot-start notice", cannotStart.length === 0, cannotStart.map((line) => line.text));
      }, { timeoutMs: 420_000 });
    } finally {
      await t.step("the tunnel settings are reset", async () => {
        patchConnection(dir, original);
        t.evidence("restart", await t.restart(server));
        t.check("no cloudflared is left", (await cloudflaredPids(container)).length === 0);
      }, STEP_OPTIONS);
    }
  },
});
