import { createPrivateKey, createPublicKey, createHash, generateKeyPairSync, sign, type KeyObject } from "node:crypto";
import { copyFileSync, cpSync, existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { join, posix } from "node:path";
import { runInJdkContainer } from "./jdk-container";
import { PANEL_JAVA_VERSION } from "./java-runtime";
import type { Logger } from "./log";
import { GRADLE_WRAPPER, REPO_ROOT, UPDATE_FIXTURE_DIR } from "./paths";
import { runProcess } from "./process";
import { productVersion, requirePluginJar, requireServerJar } from "./product";
import { replaceSharedFile } from "./shared-files";
import { readTextFile, removePath, sha256OfFile, writeText } from "./util";

export const RELEASE_NAMES = ["base", "patch", "broken", "minor"] as const;
export type ReleaseName = (typeof RELEASE_NAMES)[number];

export const ENVELOPE_FILE = "modl-release.json";

export interface ReleaseFixture {
  version: string;
  directory: string;
  server: string;
  embedded: string;
  plugin: string;
  envelope: string;
}

const BUILD_TIMEOUT_MS = 1_800_000;
const JAR_TOOL_TIMEOUT_MS = 300_000;
const WORK_MOUNT = "/work";
const PRIVATE_KEY_FILE = join(UPDATE_FIXTURE_DIR, "release-key.pem");
const PUBLIC_KEYS_FILE = join(UPDATE_FIXTURE_DIR, "release-keys.txt");
const READY_FILE = join(UPDATE_FIXTURE_DIR, "ready.txt");
const KEY_ID_LENGTH = 16;
const FIXTURE_COMMIT = "0".repeat(40);
const FIXTURE_DIGEST = `sha256:${"0".repeat(64)}`;
const FIXTURE_PUBLISHED_AT = "2026-01-01T00:00:00Z";
const FIXTURE_IMAGE = "ghcr.io/modl-gg/modl";
const DIALECTS = ["sqlite", "mysql", "postgresql"] as const;
const BROKEN_MIGRATION = "V9000__broken_release.sql";
const FAILS_ONLY_ON_AN_EXISTING_INSTANCE = [
  "CREATE TABLE modl_broken_release (id INTEGER NOT NULL CHECK (id > 0));",
  "INSERT INTO modl_broken_release SELECT 0 FROM modl_instance;",
  "",
].join("\n");

export function releaseVersion(name: ReleaseName): string {
  const base = productVersion();
  const [major, minor, patch] = base.split(/[.-]/, 3).map(Number);
  if (major === undefined || minor === undefined || patch === undefined) throw new Error(`modl.version ${base} is not MAJOR.MINOR.PATCH`);
  switch (name) {
    case "base":
      return base;
    case "patch":
    case "broken":
      return `${major}.${minor}.${patch + 1}`;
    case "minor":
      return `${major}.${minor + 1}.0`;
  }
}

export function releaseLine(name: ReleaseName): string {
  return releaseVersion(name).split(".").slice(0, 2).join(".");
}

export function releaseFixture(name: ReleaseName): ReleaseFixture {
  const version = releaseVersion(name);
  const directory = join(UPDATE_FIXTURE_DIR, name);
  return {
    version,
    directory,
    server: join(directory, `modl-server-${version}.jar`),
    embedded: join(directory, `modl-embedded-${version}.zip`),
    plugin: join(directory, `modl-${version}.jar`),
    envelope: join(directory, ENVELOPE_FILE),
  };
}

function signingKey(): KeyObject {
  if (existsSync(PRIVATE_KEY_FILE)) return createPrivateKey(readFileSync(PRIVATE_KEY_FILE));
  const { privateKey } = generateKeyPairSync("ed25519");
  writeFileSync(PRIVATE_KEY_FILE, privateKey.export({ type: "pkcs8", format: "pem" }), { mode: 0o600 });
  return privateKey;
}

function publicKeyDer(privateKey: KeyObject): Buffer {
  return createPublicKey(privateKey).export({ type: "spki", format: "der" });
}

async function fixtureInputs(publicKey: Buffer): Promise<string> {
  const jars = await Promise.all([sha256OfFile(requirePluginJar()), sha256OfFile(requireServerJar())]);
  return [productVersion(), publicKey.toString("base64"), ...jars].join("\n");
}

async function buildRelease(name: ReleaseName, logger: Logger): Promise<void> {
  const fixture = releaseFixture(name);
  logger.info(`building the ${name} test release ${fixture.version}`);
  const args = [
    "dist",
    `-Pmodl.version=${fixture.version}`,
    `-Pmodl.releaseKeysFile=${PUBLIC_KEYS_FILE}`,
    `-Pmodl.distDir=${fixture.directory}`,
  ];
  await runProcess(GRADLE_WRAPPER, args, { cwd: REPO_ROOT, timeoutMs: BUILD_TIMEOUT_MS });
}

async function jarTool(workdir: string, args: string[], subdirectory = "."): Promise<void> {
  await runInJdkContainer({
    workdir: posix.join(WORK_MOUNT, subdirectory),
    volumes: [`${workdir}:${WORK_MOUNT}`],
    command: ["jar", ...args],
    timeoutMs: JAR_TOOL_TIMEOUT_MS,
  });
}

function writeBrokenMigration(classesRoot: string): void {
  for (const dialect of DIALECTS) {
    writeText(join(classesRoot, "db", "migration", dialect, BROKEN_MIGRATION), FAILS_ONLY_ON_AN_EXISTING_INSTANCE);
  }
}

async function reindex(payload: string, library: string): Promise<void> {
  const indexFile = join(payload, "index.txt");
  const file = join(payload, "lib", library);
  const line = `packed ${await sha256OfFile(file)} ${statSync(file).size} ${library}`;
  const lines = readFileSync(indexFile, "utf8").split("\n");
  const index = lines.findIndex((entry) => entry.startsWith("packed ") && entry.endsWith(` ${library}`));
  if (index < 0) throw new Error(`${indexFile} does not pack ${library}`);
  lines[index] = line;
  writeText(indexFile, lines.join("\n"));
}

async function breakRelease(): Promise<void> {
  const patch = releaseFixture("patch");
  const broken = releaseFixture("broken");
  const work = join(UPDATE_FIXTURE_DIR, "breaking");
  removePath(work);
  removePath(broken.directory);
  mkdirSync(join(work, "payload"), { recursive: true });
  cpSync(patch.directory, broken.directory, { recursive: true });
  copyFileSync(patch.server, join(work, "server.jar"));
  copyFileSync(patch.embedded, join(work, "embedded.zip"));
  writeBrokenMigration(join(work, "boot", "BOOT-INF", "classes"));
  writeBrokenMigration(join(work, "plain"));
  await jarTool(work, ["--update", "--file", "server.jar", "-C", "boot", "BOOT-INF"]);
  await jarTool(work, ["--extract", "--file", "../embedded.zip"], "payload");
  const library = `modl-server-${patch.version}-plain.jar`;
  await jarTool(work, ["--update", "--file", `payload/lib/${library}`, "-C", "plain", "db"]);
  await reindex(join(work, "payload"), library);
  await jarTool(work, ["--create", "--no-manifest", "--file", "rebuilt.zip", "-C", "payload", "."]);
  copyFileSync(join(work, "server.jar"), broken.server);
  copyFileSync(join(work, "rebuilt.zip"), broken.embedded);
  removePath(work);
}

async function manifestArtifact(path: string, file: string): Promise<{ file: string; sha256: string; size: number }> {
  return { file, sha256: await sha256OfFile(path), size: statSync(path).size };
}

async function signRelease(name: ReleaseName, privateKey: KeyObject): Promise<void> {
  const fixture = releaseFixture(name);
  const { version } = fixture;
  const manifest = {
    format: 1,
    product: "modl",
    version,
    channel: version.includes("-") ? "prerelease" : "stable",
    commit: FIXTURE_COMMIT,
    publishedAt: FIXTURE_PUBLISHED_AT,
    critical: false,
    minUpgradeFrom: releaseVersion("base"),
    minJava: PANEL_JAVA_VERSION,
    launcherApi: 1,
    embeddedHostApi: 1,
    artifacts: {
      server: await manifestArtifact(fixture.server, `modl-server-${version}.jar`),
      embedded: await manifestArtifact(fixture.embedded, `modl-embedded-${version}.zip`),
      plugin: await manifestArtifact(fixture.plugin, `modl-${version}.jar`),
    },
    image: { repository: FIXTURE_IMAGE, digest: FIXTURE_DIGEST },
  };
  const payload = Buffer.from(JSON.stringify(manifest));
  const envelope = {
    format: 1,
    keyId: createHash("sha256").update(publicKeyDer(privateKey)).digest("hex").slice(0, KEY_ID_LENGTH),
    payload: payload.toString("base64"),
    signature: sign(null, payload, privateKey).toString("base64"),
  };
  writeText(fixture.envelope, JSON.stringify(envelope));
}

export async function prepareReleaseFixtures(logger: Logger): Promise<void> {
  mkdirSync(UPDATE_FIXTURE_DIR, { recursive: true });
  const privateKey = signingKey();
  const publicKey = publicKeyDer(privateKey);
  const inputs = await fixtureInputs(publicKey);
  if (readTextFile(READY_FILE) === inputs) return;
  removePath(READY_FILE);
  writeText(PUBLIC_KEYS_FILE, `${publicKey.toString("base64")}\n`);
  for (const name of ["minor", "patch", "base"] as const) await buildRelease(name, logger);
  await breakRelease();
  for (const name of RELEASE_NAMES) await signRelease(name, privateKey);
  writeText(READY_FILE, inputs);
}

export interface ActiveRelease {
  server: string;
  plugin: string;
}

export function activeRelease(directory: string): ActiveRelease {
  return { server: join(directory, "modl-server.jar"), plugin: join(directory, "modl.jar") };
}

export async function activateBaseRelease(directory: string): Promise<ActiveRelease> {
  const active = activeRelease(directory);
  const base = releaseFixture("base");
  mkdirSync(directory, { recursive: true });
  await replaceSharedFile(base.server, active.server);
  await replaceSharedFile(base.plugin, active.plugin);
  return active;
}
