import { mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { PANEL_JAVA_VERSION } from "../java-runtime";
import { lineOf } from "../lines";
import { ConsolePatterns } from "../contract";
import { REPO_ROOT, ROOT_COMPOSE_BUILD_FILE, ROOT_COMPOSE_FILE } from "../paths";
import { settings } from "../settings";
import { requireServer, type EnvironmentState, type UpOptions } from "../state";
import { writeText } from "../util";
import { backendJvmOptions, CONTAINER_DATA_DIR, javaService, paperService, renderCompose } from "./compose";
import { modlConfigPatches, remoteConnectionPatches, serverFeaturesPatches } from "./modl-config";
import { ownerApiKey } from "./owner-api-key";
import { releaseMirrorEnvironment } from "./release-mirror";
import { installPlugins, patchConnection, writeConnection, writeModlConfig, writePaperFiles, writeServerFeatures } from "./server-files";
import type { BootContext, BootStep, PrepareContext, ServerSpec, TopologyDefinition } from "./types";

const BACKEND = "backend";
const PAPER = "paper";
const BACKEND_SERVICE = "modl";
const REMOTE_URL = `http://${BACKEND_SERVICE}:8080`;
const PANEL_PORT = 18125;
const IMAGE_SERVER_JAR = "/app/modl-server.jar";

function backendSpec(options: UpOptions): ServerSpec {
  return {
    name: BACKEND,
    kind: "backend",
    role: "backend",
    service: BACKEND_SERVICE,
    memory: null,
    ports: { http: PANEL_PORT },
    dataMount: options.remoteBackend === "compose" ? "docker-volume" : "host",
  };
}

function paperSpec(): ServerSpec {
  return {
    name: PAPER,
    kind: "paper",
    role: "remote-paper",
    service: PAPER,
    memory: settings().remotePaper,
    ports: { game: 25702, rcon: 25753 },
  };
}

function overrideFile(state: EnvironmentState): string {
  return join(dirname(state.composeFile), "backend.override.yml");
}

function instanceEnvironment(state: EnvironmentState): Record<string, string> {
  return {
    MODL_SERVER_NAME: settings().serverName,
    ...releaseMirrorEnvironment(state.options.updateFixture),
    ...(state.options.presetAdminEmail ? { MODL_ADMIN_EMAIL: state.options.presetAdminEmail, MODL_PUBLIC_URL: state.instance.baseUrl } : {}),
  };
}

function jarBackendEnvironment(state: EnvironmentState): Record<string, string> {
  return { MODL_DATA_DIR: CONTAINER_DATA_DIR, MODL_PORT: "8080", MODL_BIND_ADDRESS: "0.0.0.0", ...instanceEnvironment(state) };
}

function fixtureJarVolume(state: EnvironmentState): string[] {
  if (!state.options.updateFixture || state.serverJar === null) return [];
  return ["    volumes:", `      - ${JSON.stringify(`${state.serverJar}:${IMAGE_SERVER_JAR}:ro`)}`];
}

function renderBackendOverride(state: EnvironmentState): string {
  const environment = Object.entries(instanceEnvironment(state)).map(([key, value]) => `      ${key}: ${JSON.stringify(value)}`);
  return [
    "services:",
    `  ${BACKEND_SERVICE}:`,
    `    container_name: ${requireServer(state, BACKEND).container}`,
    "    env_file: !reset []",
    "    ports: !override",
    `      - "127.0.0.1:${PANEL_PORT}:8080"`,
    '    restart: "no"',
    "    environment:",
    ...environment,
    ...fixtureJarVolume(state),
    "",
  ].join("\n");
}

export const remote: TopologyDefinition = {
  name: "remote",
  description: "modl server hosted separately (server jar, or the repository compose stack) + Paper in mode: remote",
  panelPort: PANEL_PORT,
  roles: { entry: PAPER, core: PAPER, data: BACKEND, notices: BACKEND },
  servers: (options: UpOptions) => [backendSpec(options), paperSpec()],
  artifacts: (options: UpOptions) => [lineOf(options).paper.artifact, "packeteventsSpigot"],
  needsServerJar: (options: UpOptions) => options.remoteBackend === "jar",
  expectedBootProblems: [],

  async prepare(context: PrepareContext): Promise<void> {
    mkdirSync(requireServer(context.state, BACKEND).dataDir, { recursive: true });
    if (context.state.options.remoteBackend === "compose") writeText(overrideFile(context.state), renderBackendOverride(context.state));
    const dir = requireServer(context.state, PAPER).dataDir;
    writePaperFiles(dir, {
      rconPassword: context.state.rconPassword,
      forwarding: { kind: "none" },
      motd: "modl remote",
      world: lineOf(context.state.options).paper.worldProperties(),
    });
    await installPlugins(dir, [context.artifactPath("packeteventsSpigot"), context.state.pluginJar]);
    writeConnection(dir, context.resources.connection, remoteConnectionPatches(REMOTE_URL, context.state.instance.apiKey ?? ""));
    writeModlConfig(dir, context.resources.config, modlConfigPatches(PAPER, context.state.options.updateFixture));
    writeServerFeatures(dir, context.resources.serverFeatures, serverFeaturesPatches(PAPER));
  },

  compose(context: PrepareContext): string {
    const paper = paperService({ state: context.state, spec: paperSpec(), heapMb: settings().remotePaper.heapMb, hostsPanel: false });
    if (context.state.options.remoteBackend === "compose") {
      return renderCompose(context.state, { [PAPER]: paper }, [{ path: [ROOT_COMPOSE_FILE, ROOT_COMPOSE_BUILD_FILE, overrideFile(context.state)], project_directory: REPO_ROOT }]);
    }
    const serverJar = context.state.serverJar;
    if (!serverJar) throw new Error("remote topology with the jar backend needs dist/modl-server-<version>.jar");
    const backend = javaService({
      state: context.state,
      spec: backendSpec(context.state.options),
      minimumJava: PANEL_JAVA_VERSION,
      jar: serverJar,
      jvmOptions: backendJvmOptions(settings().backendHeapMb),
      serverArgs: "",
      environment: jarBackendEnvironment(context.state),
      pterodactyl: false,
    });
    return renderCompose(context.state, { [BACKEND_SERVICE]: backend, [PAPER]: paper });
  },

  async boot(context: BootContext): Promise<BootStep[]> {
    const backendSince = await context.mark(BACKEND);
    await context.composeUp([BACKEND_SERVICE], { build: context.state.options.remoteBackend === "compose" });
    const panelSeconds = await context.waitForPanel();
    const listening = await context.log(BACKEND).find(ConsolePatterns.backendListening, backendSince);
    return [
      {
        step: `modl server (${context.state.options.remoteBackend}) healthy`,
        seconds: panelSeconds,
        detail: { panel: context.instance.baseUrl, listening: listening.map((line) => line.text) },
      },
    ];
  },

  async connect(context: BootContext): Promise<BootStep[]> {
    const owner = await ownerApiKey(context);
    patchConnection(requireServer(context.state, PAPER).dataDir, remoteConnectionPatches(REMOTE_URL, owner.apiKey));
    const paperSince = await context.mark(PAPER);
    await context.composeUp([PAPER]);
    const paperSeconds = await context.waitForMinecraft(PAPER, paperSince, "core");
    return [owner.step, { step: "paper booted with modl in remote mode", seconds: paperSeconds, detail: { remoteUrl: REMOTE_URL } }];
  },
};
