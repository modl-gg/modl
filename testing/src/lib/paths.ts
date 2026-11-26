import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

export const HARNESS_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
export const REPO_ROOT = resolve(process.env["MODL_REPO_ROOT"] ?? join(HARNESS_ROOT, ".."));
export const SOURCE_DIR = join(HARNESS_ROOT, "src");
export const CACHE_DIR = join(HARNESS_ROOT, ".cache");
export const UPDATE_FIXTURE_DIR = join(CACHE_DIR, "updates");
export const ARTIFACT_DIR = join(CACHE_DIR, "artifacts");
export const BUNDLER_DIR = join(CACHE_DIR, "bundler");
const LANE_NAME = /^[a-z0-9-]{1,32}$/;

function laneFromEnvironment(): string | null {
  const lane = process.env["MODL_TEST_LANE"] ?? "";
  if (lane === "") return null;
  if (!LANE_NAME.test(lane)) throw new Error(`MODL_TEST_LANE must match ${LANE_NAME.source}`);
  return lane;
}

export const HARNESS_LANE = laneFromEnvironment();
export const LANES_DIR = join(HARNESS_ROOT, "runtime-lanes");
export const RUNTIME_DIR = HARNESS_LANE === null ? join(HARNESS_ROOT, "runtime") : join(LANES_DIR, HARNESS_LANE);
export const REPORTS_DIR = join(HARNESS_ROOT, "reports");
export const CONTAINER_ASSETS_DIR = join(HARNESS_ROOT, "docker");
export const STATE_FILE = join(RUNTIME_DIR, "state.json");
export const RELEASE_MIRROR_DIR = join(RUNTIME_DIR, "releases");
export const MARKS_FILE = join(RUNTIME_DIR, "marks.json");
export const BOT_DAEMON_FILE = join(RUNTIME_DIR, "botd.json");
export const BOT_DAEMON_LOG = join(RUNTIME_DIR, "botd.log");
export const BOT_DAEMON_SPAWN_LOCK = join(RUNTIME_DIR, "botd.lock");
export const LITEBANS_FIXTURE_JAR = join(HARNESS_ROOT, "fixtures", "litebans", "build", "libs", "litebans-fixture.jar");
export const DIST_DIR = join(REPO_ROOT, "dist");
export const GRADLE_WRAPPER = join(REPO_ROOT, "gradlew");
export const ROOT_GRADLE_PROPERTIES = join(REPO_ROOT, "gradle.properties");
export const ROOT_COMPOSE_FILE = join(REPO_ROOT, "docker-compose.yml");
export const ROOT_COMPOSE_BUILD_FILE = join(REPO_ROOT, "docker-compose.build.yml");

export function topologyRuntimeDir(topology: string, line: string): string {
  return join(RUNTIME_DIR, topology, line);
}

export function activeReleaseDir(topology: string, line: string): string {
  return join(topologyRuntimeDir(topology, line), "active-release");
}

export function snapshotDir(topology: string, line: string): string {
  return join(topologyRuntimeDir(topology, line), "update-snapshot");
}

export function serverDataDir(topology: string, line: string, server: string): string {
  return join(topologyRuntimeDir(topology, line), "servers", server);
}
