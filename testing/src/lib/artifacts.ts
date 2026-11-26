import { copyFileSync, createWriteStream, existsSync, mkdirSync, readdirSync, renameSync, rmSync } from "node:fs";
import { join } from "node:path";
import { Readable } from "node:stream";
import { pipeline } from "node:stream/promises";
import { z } from "zod";
import { requestSignal } from "./cancellation";
import type { Logger } from "./log";
import { ARTIFACT_DIR } from "./paths";
import { settings } from "./settings";
import { sha256OfFile } from "./util";

type FillProject = "paper" | "folia" | "velocity";

type PacketEventsPlatform = "spigot" | "velocity" | "bungeecord";

interface FillSource {
  kind: "fill";
  project: FillProject;
  version: string;
  build: number;
}

type ArtifactSource = FillSource | { kind: "url"; url: string };

export interface ArtifactPin {
  file: string;
  version: string;
  sha256: string;
  source: ArtifactSource;
}

export interface ServerPin extends ArtifactPin {
  java: number;
}

export interface FillPin extends ServerPin {
  source: FillSource;
}

const PACKETEVENTS_VERSION = "2.14.0";

function fillPin(project: FillProject, version: string, build: number, java: number, sha256: string): FillPin {
  return { file: `${project}-${version}-${build}.jar`, version: `${version}-${build}`, sha256, java, source: { kind: "fill", project, version, build } };
}

function urlPin(file: string, version: string, url: string, sha256: string): ArtifactPin {
  return { file, version, sha256, source: { kind: "url", url } };
}

function packetEventsPin(platform: PacketEventsPlatform, sha256: string): ArtifactPin {
  const file = `packetevents-${platform}-${PACKETEVENTS_VERSION}.jar`;
  return {
    file,
    version: PACKETEVENTS_VERSION,
    sha256,
    source: { kind: "url", url: `https://github.com/retrooper/packetevents/releases/download/v${PACKETEVENTS_VERSION}/${file}` },
  };
}

export const ARTIFACTS = {
  paperLatest: fillPin("paper", "26.1.2", 74, 25, "1d70b1dab9cf4a6de615209a536f3a45a2186240253c428213ce2188ab95e5f7"),
  paper121: fillPin("paper", "1.21.11", 132, 21, "5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba"),
  paperLegacy: fillPin("paper", "1.8.8", 445, 8, "7ff6d2cec671ef0d95b3723b5c92890118fb882d73b7f8fa0a2cd31d97c55f86"),
  foliaLatest: fillPin("folia", "26.1.2", 8, 25, "607afd1c3320008e1ffd2eaee6780ace4419d5f8c527b75e79f259be79ebf57b"),
  velocityLatest: fillPin("velocity", "4.3.1", 45, 25, "0844bcabdcdb4f5b875906fd4c3365bc1bb76918e42bc595ed88ad195258dd75"),
  velocity121: fillPin("velocity", "3.5.1", 615, 21, "b4e3164df5377346854dc6cb9e6a78022b1946ff69e89676313f5f6f1c6f0fb3"),
  packeteventsSpigot: packetEventsPin("spigot", "060087c58ec268eae7dd0eb1f17ac3bea1eaadda1693414141ff64e506091bd7"),
  packeteventsVelocity: packetEventsPin("velocity", "0772f64b51aaf79132c76bb1503707ea0d35433713d5312e67401eaa599d271b"),
  packeteventsBungeecord: packetEventsPin("bungeecord", "21c94ee93796161df7e6a24f66a02f2f210ee12df15aecec7f821d6356d60326"),
  advancedban: urlPin(
    "AdvancedBan-Bundle-2.3.0-RELEASE.jar",
    "2.3.0",
    "https://github.com/DevLeoko/AdvancedBan/releases/download/v2.3.0/AdvancedBan-Bundle-2.3.0-RELEASE.jar",
    "7b4965adab8a8110e28fcc14603be7148273c2e543014d915ddf44a639031dd8",
  ),
  libertybans: urlPin(
    "LibertyBans_Release-1.1.4.jar",
    "1.1.4",
    "https://github.com/A248/LibertyBans/releases/download/1.1.4/LibertyBans_Release-1.1.4.jar",
    "57303df011ff49747856af4ed818546f19b5dd84b8c627a6666431d0df962634",
  ),
  libertybansImplementation: urlPin(
    "bans-download-1.1.4.jar",
    "1.1.4",
    "https://dl.cloudsmith.io/public/anand-beh/affero-gpl3/maven/space/arim/libertybans/bans-download/1.1.4/bans-download-1.1.4.jar",
    "db9bcb3767439fff989045606e787e7bec446f0d703097c02991269d55e5c846",
  ),
  banmanager: urlPin(
    "BanManagerBukkit-7.11.0.jar",
    "7.11.0",
    "https://github.com/BanManagement/BanManager/releases/download/v7.11.0/BanManagerBukkit.jar",
    "e26f7c6189954a6182643d76809baaf656a146d6aeee28a079d08400d29aff24",
  ),
  banmanagerBungeecord: urlPin(
    "BanManagerBungeeCord-7.11.0.jar",
    "7.11.0",
    "https://github.com/BanManagement/BanManager/releases/download/v7.11.0/BanManagerBungeeCord.jar",
    "eaac6d2f16b995f4f80c37c04007e858c612510338e24ea15c32c5414fc56070",
  ),
  banmanagerVelocity: urlPin(
    "BanManagerVelocity-7.11.0.jar",
    "7.11.0",
    "https://github.com/BanManagement/BanManager/releases/download/v7.11.0/BanManagerVelocity.jar",
    "96c5e95b373018ae681f92cd7e5a6214767db9465f94aea35f380e31b54b274a",
  ),
  bansystemArchive: urlPin(
    "BanSystem-3.1.zip",
    "3.1",
    "https://github.com/antisocialtobi/BanSystem-v2/releases/download/v3.1/BanSystem-3.1.zip",
    "e604e9b0ebe07894de6a4aeddc1abe77e504536c7a6a7847daa4e6278afedca8",
  ),
  floodgateVelocity: urlPin(
    "floodgate-velocity-2.2.5-141.jar",
    "2.2.5 build 141",
    "https://download.geysermc.org/v2/projects/floodgate/versions/2.2.5/builds/141/downloads/velocity",
    "649835022de37ff09255fc626d4bfe7bc2a3b926d07b0dfaf1f51e12bf37001d",
  ),
  floodgateBungeecord: urlPin(
    "floodgate-bungee-2.2.5-141.jar",
    "2.2.5 build 141",
    "https://download.geysermc.org/v2/projects/floodgate/versions/2.2.5/builds/141/downloads/bungee",
    "ba79b19048e1255201ed886af5b437038d428e6e673d9756a8704235473c1544",
  ),
  essentials: urlPin(
    "EssentialsX-2.22.0.jar",
    "2.22.0",
    "https://github.com/EssentialsX/Essentials/releases/download/2.22.0/EssentialsX-2.22.0.jar",
    "bda4685105977fca2e209820a9f0ad24275bd103390a03236f38e59bfdac58e6",
  ),
  viaversion: urlPin(
    "ViaVersion-5.12.2.jar",
    "5.12.2",
    "https://github.com/ViaVersion/ViaVersion/releases/download/5.12.2/ViaVersion-5.12.2.jar",
    "24e4876fefe73e08a4c87cd6c457ee0916e62dd69656631caf41e26f909d504b",
  ),
  viabackwards: urlPin(
    "ViaBackwards-5.12.2.jar",
    "5.12.2",
    "https://github.com/ViaVersion/ViaBackwards/releases/download/5.12.2/ViaBackwards-5.12.2.jar",
    "9a148a80ea22074f4bd1626cdbe254a4cd998bf44e7ceaff4e285a1b6b35da42",
  ),
  viarewind: urlPin(
    "ViaRewind-4.2.0.jar",
    "4.2.0",
    "https://github.com/ViaVersion/ViaRewind/releases/download/4.2.0/ViaRewind-4.2.0.jar",
    "d6634ba57bb82d5161c68dfb393571cdf40511a0beb1b04b8c7ed794a3532c6a",
  ),
  bungeecord: {
    file: "bungeecord-2111.jar",
    version: "26.1-R0.1-SNAPSHOT build 2111",
    sha256: "1d9dbc31b57b22a37553f160ac1b61762717e9e4a6a1341f3dd27a1f6f2671fd",
    java: 17,
    source: {
      kind: "url",
      url: "https://hub.spigotmc.org/jenkins/job/BungeeCord/2111/artifact/bootstrap/target/BungeeCord.jar",
    },
  },
} as const satisfies Record<string, ArtifactPin | ServerPin>;

export type ArtifactId = keyof typeof ARTIFACTS;

function isArtifactId(value: string): value is ArtifactId {
  return Object.hasOwn(ARTIFACTS, value);
}

export const ARTIFACT_IDS: readonly ArtifactId[] = Object.keys(ARTIFACTS).filter(isArtifactId);

export type PaperArtifactId = Extract<ArtifactId, `paper${string}` | `folia${string}`>;

export type VelocityArtifactId = Extract<ArtifactId, `velocity${string}`>;

export type ProxyArtifactId = VelocityArtifactId | "bungeecord";

export function artifactPath(id: ArtifactId): string {
  return join(ARTIFACT_DIR, ARTIFACTS[id].file);
}

const fillBuildSchema = z.object({
  downloads: z
    .record(z.string(), z.object({ url: z.string().optional(), checksums: z.object({ sha256: z.string().optional() }).optional() }))
    .optional(),
});

async function resolveDownloadUrl(pin: ArtifactPin): Promise<string> {
  if (pin.source.kind === "url") return pin.source.url;
  const { project, version, build } = pin.source;
  const endpoint = `https://fill.papermc.io/v3/projects/${project}/versions/${version}/builds/${build}`;
  const response = await fetch(endpoint, {
    headers: { "User-Agent": settings().userAgent, Accept: "application/json" },
    signal: requestSignal(30_000),
  });
  if (!response.ok) throw new Error(`PaperMC Fill ${endpoint} returned ${response.status}`);
  const serverDownload = fillBuildSchema.parse(await response.json()).downloads?.["server:default"];
  if (!serverDownload?.url) throw new Error(`PaperMC Fill ${endpoint} has no server:default download`);
  const reported = serverDownload.checksums?.sha256;
  if (reported !== pin.sha256) {
    throw new Error(`PaperMC Fill reports sha256 ${reported ?? "none"} for ${pin.file}, pinned ${pin.sha256}`);
  }
  return serverDownload.url;
}

async function download(url: string, destination: string): Promise<void> {
  const response = await fetch(url, {
    headers: { "User-Agent": settings().userAgent },
    redirect: "follow",
    signal: requestSignal(600_000),
  });
  if (!response.ok || !response.body) throw new Error(`download of ${url} failed: HTTP ${response.status}`);
  await pipeline(Readable.fromWeb(response.body), createWriteStream(destination));
}

async function matchesPin(file: string, pin: ArtifactPin): Promise<boolean> {
  return existsSync(file) && (await sha256OfFile(file)) === pin.sha256;
}

async function seedFromDirectory(pin: ArtifactPin, destination: string): Promise<boolean> {
  const seedDir = settings().artifactSeedDir;
  if (!seedDir) return false;
  const candidate = join(seedDir, pin.file);
  if (!(await matchesPin(candidate, pin))) return false;
  copyFileSync(candidate, destination);
  return true;
}

export async function ensureArtifact(id: ArtifactId, logger: Logger): Promise<string> {
  const pin: ArtifactPin = ARTIFACTS[id];
  const destination = artifactPath(id);
  mkdirSync(ARTIFACT_DIR, { recursive: true });
  if (await matchesPin(destination, pin)) return destination;
  rmSync(destination, { force: true });
  if (await seedFromDirectory(pin, destination)) {
    logger.info(`seeded ${pin.file} from ${settings().artifactSeedDir ?? ""}`);
    return destination;
  }
  const partial = `${destination}.part`;
  logger.info(`downloading ${pin.file}`);
  await download(await resolveDownloadUrl(pin), partial);
  const actual = await sha256OfFile(partial);
  if (actual !== pin.sha256) {
    rmSync(partial, { force: true });
    throw new Error(`sha256 mismatch for ${pin.file}: got ${actual}, pinned ${pin.sha256}`);
  }
  renameSync(partial, destination);
  return destination;
}

export async function ensureArtifacts(ids: readonly ArtifactId[], logger: Logger): Promise<void> {
  await Promise.all(ids.map(async (id) => ensureArtifact(id, logger)));
}

export function jarsUnder(directory: string): string[] {
  if (!existsSync(directory)) return [];
  return readdirSync(directory, { recursive: true, encoding: "utf8" })
    .filter((file) => file.endsWith(".jar"))
    .map((file) => join(directory, file));
}

export interface ArtifactStatus {
  id: ArtifactId;
  file: string;
  version: string;
  cached: boolean;
}

export async function artifactStatuses(): Promise<ArtifactStatus[]> {
  return Promise.all(
    ARTIFACT_IDS.map(async (id) => ({
      id,
      file: ARTIFACTS[id].file,
      version: ARTIFACTS[id].version,
      cached: await matchesPin(artifactPath(id), ARTIFACTS[id]),
    })),
  );
}
