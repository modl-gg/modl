import { basename, dirname, join } from "node:path";
import { ARTIFACTS, artifactPath } from "../lib/artifacts";
import { yamlText } from "../lib/config-files";
import { PANEL_JAVA_VERSION, temurinJre } from "../lib/java-runtime";
import { PAPER_BUILDS, type PaperLaunch } from "../lib/paper";
import { CONTAINER_ASSETS_DIR } from "../lib/paths";
import { hostUser, settings } from "../lib/settings";
import { CONTAINER_DATA_DIR, identityMounts, velocityJvmOptions, writeIdentityFiles } from "../lib/topologies/compose";
import { embeddedServerDir } from "../lib/topologies/server-files";
import { writeText } from "../lib/util";
import {
  DEV_COMPOSE_FILE,
  DEV_IDENTITY_DIR,
  DEV_LABEL,
  DEV_PROJECT,
  DEV_SERVER_NAMES,
  DEV_SERVERS,
  devContainerName,
  devServerDir,
  LOG_VIEWER_SERVICE,
  LOGS_CONTAINER_PORT,
  MINECRAFT_PORT,
  PANEL_LOG_SERVICE,
  type DevServerName,
} from "./layout";
import type { DevPorts } from "./state";

export const LOG_VIEWER_IMAGE = "amir20/dozzle:v11.4.2@sha256:08cd5230e21e364241f3dcf26191f293166f7835654eb7e41168b9c685f0947b";

const DIRECT_MEMORY_CAP = "-XX:MaxDirectMemorySize=192m";
const LOG_ROTATION = { driver: "json-file", options: { "max-size": "10m", "max-file": "3" } };
const PROXY_JAVA = Math.max(ARTIFACTS.velocityLatest.java, PANEL_JAVA_VERSION);

interface DevLaunch extends PaperLaunch {
  image: string;
}

function velocityLaunch(heapMb: number): DevLaunch {
  return {
    image: temurinJre(PROXY_JAVA),
    jar: artifactPath("velocityLatest"),
    jvmOptions: velocityJvmOptions(heapMb),
    serverArgs: "",
    extraVolumes: [],
  };
}

function launchOf(name: DevServerName): DevLaunch {
  const heapMb = DEV_SERVERS[name].memory.heapMb;
  switch (name) {
    case "proxy":
    case "botgate":
      return velocityLaunch(heapMb);
    case "latest":
      return { image: temurinJre(PAPER_BUILDS.paperLatest.java), ...PAPER_BUILDS.paperLatest.launch(heapMb) };
    case "legacy":
      return { image: temurinJre(PAPER_BUILDS.paperLegacy.java), ...PAPER_BUILDS.paperLegacy.launch(heapMb) };
  }
}

function publishedPorts(name: DevServerName, ports: DevPorts): string[] {
  switch (name) {
    case "proxy":
      return [`127.0.0.1:${ports.join}:${MINECRAFT_PORT}`, `127.0.0.1:${ports.panel}:${ports.panel}`];
    case "botgate":
      return [`127.0.0.1:${ports.botGate}:${MINECRAFT_PORT}`];
    case "latest":
    case "legacy":
      return [];
  }
}

function hardening(memoryLimit: string): Record<string, unknown> {
  return {
    read_only: true,
    mem_limit: memoryLimit,
    memswap_limit: memoryLimit,
    pids_limit: settings().pidsLimit,
    cap_drop: ["ALL"],
    security_opt: ["no-new-privileges:true"],
  };
}

function minecraftService(name: DevServerName, ports: DevPorts): Record<string, unknown> {
  const server = DEV_SERVERS[name];
  const launch = launchOf(name);
  return {
    image: launch.image,
    container_name: devContainerName(name),
    hostname: name,
    labels: { [DEV_LABEL]: "1" },
    user: hostUser(),
    working_dir: CONTAINER_DATA_DIR,
    entrypoint: ["/bin/sh", "/modl-testing/run.sh"],
    environment: {
      SERVER_JAR: `/server/${basename(launch.jar)}`,
      JVM_OPTS: [...launch.jvmOptions, DIRECT_MEMORY_CAP].join(" "),
      SERVER_ARGS: launch.serverArgs,
    },
    volumes: [
      `${devServerDir(name)}:${CONTAINER_DATA_DIR}`,
      `${CONTAINER_ASSETS_DIR}:/modl-testing:ro`,
      `${dirname(launch.jar)}:/server:ro`,
      ...identityMounts(DEV_IDENTITY_DIR),
      ...launch.extraVolumes,
    ],
    ports: publishedPorts(name, ports),
    stop_grace_period: "60s",
    restart: "no",
    logging: LOG_ROTATION,
    tmpfs: [`/tmp:size=${settings().tmpSize},mode=1777,exec`],
    ...hardening(server.memory.limit),
  };
}

function panelLogService(): Record<string, unknown> {
  return {
    image: temurinJre(PROXY_JAVA),
    container_name: devContainerName(PANEL_LOG_SERVICE),
    labels: { [DEV_LABEL]: "1" },
    user: hostUser(),
    entrypoint: ["tail", "-n", "200", "-F", "/logs/server.log"],
    volumes: [`${join(embeddedServerDir(devServerDir("proxy")), "logs")}:/logs:ro`],
    restart: "no",
    logging: LOG_ROTATION,
    ...hardening("32m"),
  };
}

function logViewerService(ports: DevPorts): Record<string, unknown> {
  return {
    image: LOG_VIEWER_IMAGE,
    container_name: devContainerName(LOG_VIEWER_SERVICE),
    labels: { [DEV_LABEL]: "1" },
    environment: {
      DOZZLE_FILTER: `label=com.docker.compose.project=${DEV_PROJECT}`,
      DOZZLE_HOSTNAME: "modl dev network",
      DOZZLE_NO_ANALYTICS: "true",
      DOZZLE_ENABLE_ACTIONS: "false",
      DOZZLE_ENABLE_SHELL: "false",
    },
    volumes: ["/var/run/docker.sock:/var/run/docker.sock:ro"],
    ports: [`127.0.0.1:${ports.logs}:${LOGS_CONTAINER_PORT}`],
    restart: "no",
    logging: LOG_ROTATION,
    mem_limit: "128m",
    memswap_limit: "128m",
    security_opt: ["no-new-privileges:true"],
  };
}

export function writeDevCompose(ports: DevPorts): string {
  writeIdentityFiles(DEV_IDENTITY_DIR);
  const minecraft = Object.fromEntries(DEV_SERVER_NAMES.map((name) => [name, minecraftService(name, ports)]));
  writeText(
    DEV_COMPOSE_FILE,
    yamlText({
      name: DEV_PROJECT,
      services: {
        ...minecraft,
        [PANEL_LOG_SERVICE]: panelLogService(),
        [LOG_VIEWER_SERVICE]: logViewerService(ports),
      },
      networks: {
        default: {
          name: DEV_PROJECT,
          labels: { [DEV_LABEL]: "1" },
        },
      },
    }),
  );
  return DEV_COMPOSE_FILE;
}
