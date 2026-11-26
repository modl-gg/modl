import { cpSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { AuthErrors, ConsoleCommands, Endpoints, punishmentIssued, UpdateEnums } from "../lib/contract";
import { apiErrorCode, HttpStatus, jsonField, jsonString, type HttpResult } from "../lib/http";
import { activeReleaseDir, snapshotDir } from "../lib/paths";
import { activeRelease, releaseFixture, releaseVersion, type ReleaseName } from "../lib/release-fixtures";
import type { UpOptions } from "../lib/state";
import { ReleaseMirror } from "../lib/topologies/release-mirror";
import { modlDir } from "../lib/topologies/server-files";
import { errorMessage, readTextFile, removePath, sleep } from "../lib/util";
import { awaitVisibleToContainers, contentFilesUnder, replaceSharedFile } from "../lib/shared-files";
import type { ScenarioContext } from "./framework";
import { expectJoinRefused, ownerSession } from "./support";

export const UPDATE_TIMEOUT_MS = 600_000;
export const RESTART_TIMEOUT_MS = 600_000;
const POLL_MS = 1000;

export function needsUpdateFixture(options: UpOptions): string | null {
  return options.updateFixture ? null : "needs the test releases (boot with up --update-fixture)";
}

export function needsFixtureAndJarBackend(options: UpOptions): string | null {
  return needsUpdateFixture(options) ?? (options.remoteBackend === "jar" ? null : "needs the server jar backend");
}

export function dataDirectory(t: ScenarioContext): string {
  const server = t.env.server(t.env.state.dataServer);
  return server.role === "backend" ? server.dataDir : join(modlDir(server.dataDir), "data");
}

export function updateFile(t: ScenarioContext, name: "pending" | "committed" | "last-failure"): string | null {
  return readTextFile(join(dataDirectory(t), "updates", `${name}.json`));
}

export function upgradeBackups(t: ScenarioContext): string[] {
  return readdirSync(join(dataDirectory(t), "backups")).filter((file) => file.startsWith("pre-update-"));
}

export async function replaceServerJar(t: ScenarioContext, name: ReleaseName): Promise<void> {
  await replaceSharedFile(releaseFixture(name).server, activeRelease(activeReleaseDir(t.topology, t.env.state.options.line)).server);
}

export async function replacePluginJar(t: ScenarioContext, server: string, name: ReleaseName): Promise<void> {
  await replaceSharedFile(releaseFixture(name).plugin, join(t.env.server(server).dataDir, "plugins", "modl.jar"));
}

export class UpdateLab {
  readonly mirror: ReleaseMirror;
  private readonly t: ScenarioContext;
  private readonly startOrder: readonly string[];
  private readonly directory: string;

  constructor(t: ScenarioContext, startOrder: readonly string[]) {
    this.t = t;
    this.startOrder = startOrder;
    this.directory = snapshotDir(t.topology, t.env.state.options.line);
    this.mirror = new ReleaseMirror(t.env.state.project);
  }

  async stopAll(): Promise<void> {
    this.t.quitAllBots();
    for (const name of [...this.startOrder].reverse()) await this.t.env.stopGracefully(name);
  }

  async startAll(): Promise<void> {
    for (const name of this.startOrder) await this.t.start(name);
  }

  save(): void {
    removePath(this.directory);
    for (const name of this.startOrder) cpSync(this.t.env.server(name).dataDir, join(this.directory, name), { recursive: true });
  }

  keepUpgradeBase(name: string): void {
    removePath(this.upgradeBase(name));
    cpSync(this.t.env.server(name).dataDir, this.upgradeBase(name), { recursive: true });
  }

  async restoreUpgradeBase(name: string): Promise<void> {
    await this.replaceDataDir(name, this.upgradeBase(name));
  }

  private upgradeBase(name: string): string {
    return join(this.directory, "upgrade-base", name);
  }

  async restoreData(name: string): Promise<void> {
    await this.replaceDataDir(name, join(this.directory, name));
  }

  private async replaceDataDir(name: string, snapshot: string): Promise<void> {
    const { dataDir } = this.t.env.server(name);
    removePath(dataDir);
    cpSync(snapshot, dataDir, { recursive: true });
    await awaitVisibleToContainers(dataDir, contentFilesUnder(dataDir));
  }

  async restoreAll(): Promise<void> {
    for (const name of this.startOrder) await this.restoreData(name);
    await replaceServerJar(this.t, "base");
    removePath(this.directory);
  }
}

export async function withUpdateLab(t: ScenarioContext, startOrder: readonly string[], body: (lab: UpdateLab) => Promise<void>): Promise<void> {
  const lab = new UpdateLab(t, startOrder);
  await t.step("the owner has a panel session", async () => ownerSession(t));
  await t.step(
    "the data is saved and the servers start again with fresh rate limits",
    async () => {
      lab.mirror.clear();
      await lab.stopAll();
      lab.save();
      await lab.startAll();
    },
    { timeoutMs: RESTART_TIMEOUT_MS, allowConsoleErrors: true },
  );
  try {
    await body(lab);
  } finally {
    await t.step(
      "the saved data and the base release are restored",
      async () => {
        await lab.stopAll();
        await lab.restoreAll();
        lab.mirror.clear();
        await lab.startAll();
      },
      { timeoutMs: RESTART_TIMEOUT_MS, allowConsoleErrors: true },
    );
  }
}

function statusBody(t: ScenarioContext, result: HttpResult): unknown {
  t.require(result.status === HttpStatus.ok, `GET ${Endpoints.updates} returned ${result.status}`);
  return result.json;
}

export async function updateStatus(t: ScenarioContext): Promise<unknown> {
  return statusBody(t, await t.instance.api(Endpoints.updates));
}

export function currentVersion(status: unknown): string | null {
  return jsonString(status, "currentVersion");
}

export async function checkNow(t: ScenarioContext): Promise<unknown> {
  const result = await t.instance.api(Endpoints.updatesCheck, { method: "POST", timeoutMs: 60_000 });
  t.require(result.status === HttpStatus.ok, `POST ${Endpoints.updatesCheck} returned ${result.status} ${apiErrorCode(result) ?? ""}`);
  return result.json;
}

export async function offer(t: ScenarioContext, mirror: ReleaseMirror, name: ReleaseName): Promise<unknown> {
  await mirror.publish(name);
  const status = await checkNow(t);
  const release = jsonField(status, "release");
  t.require(jsonString(release, "version") === releaseVersion(name), `modl ${releaseVersion(name)} was not offered: ${JSON.stringify(status)}`);
  return release;
}

export async function install(t: ScenarioContext, name: ReleaseName): Promise<HttpResult> {
  const result = await t.instance.api(Endpoints.updatesInstall, {
    method: "POST",
    json: { version: releaseVersion(name), acknowledgePluginUpdate: false },
  });
  t.evidence("install", { status: result.status, code: apiErrorCode(result) });
  return result;
}

export async function installOffered(t: ScenarioContext, mirror: ReleaseMirror, name: ReleaseName): Promise<void> {
  const release = await offer(t, mirror, name);
  t.check("the update can be installed", jsonString(release, "installAvailability") === UpdateEnums.available, release);
  const started = await install(t, name);
  t.require(started.status === HttpStatus.accepted, `the install was refused with ${started.status}: ${started.text}`);
}

export interface StatusAcrossRestore {
  status: unknown;
  signedOut: boolean;
}

async function pollStatus(
  t: ScenarioContext,
  description: string,
  accept: (status: unknown) => boolean,
  signInAgain: boolean,
): Promise<StatusAcrossRestore> {
  const deadline = Date.now() + UPDATE_TIMEOUT_MS;
  let last = "no answer";
  let signedOut = false;
  while (Date.now() < deadline) {
    let result: HttpResult | null = null;
    try {
      result = await t.instance.api(Endpoints.updates);
    } catch (error) {
      last = errorMessage(error);
    }
    if (result?.status === HttpStatus.unauthorized) {
      if (apiErrorCode(result) !== AuthErrors.unauthenticated) {
        return t.fail(`${description}: GET ${Endpoints.updates} returned 401 ${apiErrorCode(result) ?? "without a code"}`);
      }
      if (!signInAgain) return t.fail(`${description}: the panel session was revoked although no restore was expected`);
      if (signedOut) return t.fail(`${description}: a fresh owner session was refused again`);
      signedOut = true;
      await t.instance.newOwnerSession();
      continue;
    }
    if (result?.status === HttpStatus.ok) {
      if (accept(result.json)) return { status: result.json, signedOut };
      last = JSON.stringify(result.json);
    } else if (result !== null) {
      last = `GET ${Endpoints.updates} returned ${result.status}`;
    }
    await sleep(POLL_MS);
  }
  return t.fail(`${description} did not happen within ${UPDATE_TIMEOUT_MS}ms (last: ${last})`);
}

export async function awaitStatus(t: ScenarioContext, description: string, accept: (status: unknown) => boolean): Promise<unknown> {
  return (await pollStatus(t, description, accept, false)).status;
}

export async function awaitStatusAcrossRestore(
  t: ScenarioContext,
  description: string,
  accept: (status: unknown) => boolean,
): Promise<StatusAcrossRestore> {
  return pollStatus(t, description, accept, true);
}

function restoredDatabase(status: unknown): boolean {
  return jsonField(jsonField(status, "lastFailure"), "databaseRestored") === true;
}

export function expectRolledBackWithRestore(t: ScenarioContext, settled: StatusAcrossRestore): void {
  const failure = jsonField(settled.status, "lastFailure");
  t.check("the database was restored", restoredDatabase(settled.status), failure);
  expectSessionMatchesRestore(t, settled);
}

export function expectSessionMatchesRestore(t: ScenarioContext, settled: StatusAcrossRestore): void {
  const restored = restoredDatabase(settled.status);
  t.check(
    restored ? "the database restore signed the owner out" : "the panel session survived",
    settled.signedOut === restored,
    { databaseRestored: restored, signedOut: settled.signedOut },
  );
}

export async function awaitRunning(t: ScenarioContext, name: ReleaseName): Promise<unknown> {
  const version = releaseVersion(name);
  return awaitStatus(t, `modl ${version} running`, (status) => currentVersion(status) === version);
}

export async function awaitRolledBack(t: ScenarioContext): Promise<StatusAcrossRestore> {
  const base = releaseVersion("base");
  return awaitStatusAcrossRestore(
    t,
    `the return to modl ${base}`,
    (status) => currentVersion(status) === base && jsonString(jsonField(status, "lastFailure"), "toVersion") !== null,
  );
}

export async function seedBan(t: ScenarioContext): Promise<string> {
  const player = t.playerName("Upd");
  await t.bot(player);
  t.quitBot(player);
  await t.consoleExpect(ConsoleCommands.ban(player, "harness update"), punishmentIssued("ban", player));
  await sleep(3000);
  return player;
}

export async function expectSessionRevokedByRestore(t: ScenarioContext): Promise<void> {
  const profile = await t.instance.api(Endpoints.profile);
  t.check(
    "the database restore signed the owner out",
    profile.status === HttpStatus.unauthorized && apiErrorCode(profile) === AuthErrors.unauthenticated,
    { status: profile.status, code: apiErrorCode(profile) },
  );
  await t.instance.newOwnerSession();
}

export async function expectSessionKept(t: ScenarioContext): Promise<void> {
  t.check("the panel session survived", (await t.instance.profile()) !== null);
}

export async function expectDataIntact(t: ScenarioContext, banned: string, writtenBefore = "the update"): Promise<void> {
  t.expectMatch(`the ban written before ${writtenBefore} is enforced`, await expectJoinRefused(t, banned), /ban/i);
  const player = await t.bot(t.playerName("Back"));
  t.check("players can join again", player.connected);
}

export async function awaitConsole(t: ScenarioContext, server: string, pattern: RegExp, since: string): Promise<string> {
  const line = await t.env.log(server).waitFor(pattern, { since, timeoutMs: UPDATE_TIMEOUT_MS });
  t.evidence(`${server}: ${pattern.source}`, line.text);
  return line.text;
}
