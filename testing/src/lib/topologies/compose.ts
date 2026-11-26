import { mkdirSync, writeFileSync } from "node:fs";
import { basename, dirname, join } from "node:path";
import { yamlText } from "../config-files";
import { harnessLabels } from "../docker";
import { panelHostJava } from "../java-runtime";
import { javaImage, lineOf } from "../lines";
import { CONTAINER_ASSETS_DIR, HARNESS_LANE } from "../paths";
import { hostUser, settings } from "../settings";
import { requireServer, type EnvironmentState, type TopologyName } from "../state";
import { RELEASE_MIRROR_SERVICE, releaseMirrorEnvironment, releaseMirrorService } from "./release-mirror";
import type { ServerSpec } from "./types";

export const CONTAINER_PORTS = { game: 25565, rcon: 25575, http: 8080 } as const;

export const CONTAINER_DATA_DIR = "/data";

const CONTAINER_ACCOUNT = "container";

function identityDir(state: EnvironmentState): string {
  return join(dirname(state.composeFile), "identity");
}

export function identityMounts(dir: string): string[] {
  return [`${join(dir, "passwd")}:/etc/passwd:ro`, `${join(dir, "group")}:/etc/group:ro`];
}

export function writeIdentityFiles(dir: string): void {
  const [uid, gid] = hostUser().split(":");
  mkdirSync(dir, { recursive: true });
  writeFileSync(join(dir, "passwd"), `root:x:0:0:root:/root:/bin/sh\n${CONTAINER_ACCOUNT}:x:${uid ?? ""}:${gid ?? ""}:${CONTAINER_ACCOUNT}:${CONTAINER_DATA_DIR}:/bin/sh\n`);
  writeFileSync(join(dir, "group"), `root:x:0:\n${CONTAINER_ACCOUNT}:x:${gid ?? ""}:\n`);
}

function identityVolumes(state: EnvironmentState): string[] {
  return identityMounts(identityDir(state));
}

function writeContainerIdentity(state: EnvironmentState): void {
  writeIdentityFiles(identityDir(state));
}

export function containerName(topology: TopologyName, server: string): string {
  return `${projectName(topology)}-${server}`;
}

export function projectName(topology: TopologyName): string {
  return HARNESS_LANE === null ? `modl-testing-${topology}` : `modl-testing-${HARNESS_LANE}-${topology}`;
}

function isPublished(port: number | undefined): port is number {
  return port !== undefined && port !== 0;
}

function publishedPorts(spec: ServerSpec): string[] {
  const mappings: string[] = [];
  const { game, rcon, http } = spec.ports;
  if (isPublished(game)) mappings.push(`127.0.0.1:${game}:${CONTAINER_PORTS.game}`);
  if (isPublished(rcon)) mappings.push(`127.0.0.1:${rcon}:${CONTAINER_PORTS.rcon}`);
  if (isPublished(http)) mappings.push(`127.0.0.1:${http}:${CONTAINER_PORTS.http}`);
  return mappings;
}

export interface JavaService {
  state: EnvironmentState;
  spec: ServerSpec;
  minimumJava: number;
  jar: string;
  jvmOptions: string[];
  serverArgs: string;
  extraVolumes?: string[];
  environment?: Record<string, string>;
  pterodactyl: boolean;
}

const MEMORY_LIMIT = /^(\d+)([mg])$/i;

function memoryMegabytes(limit: string): number {
  const match = MEMORY_LIMIT.exec(limit);
  if (!match?.[1] || !match[2]) throw new Error(`memory limit '${limit}' must look like 3g or 2048m`);
  return Number(match[1]) * (match[2].toLowerCase() === "g" ? 1024 : 1);
}

function pterodactylEnvironment(spec: ServerSpec): Record<string, string> {
  return {
    SERVER_IP: "0.0.0.0",
    SERVER_PORT: String(CONTAINER_PORTS.game),
    ...(spec.memory ? { SERVER_MEMORY: String(memoryMegabytes(spec.memory.limit)) } : {}),
  };
}

function pterodactylConstraints(spec: ServerSpec): Record<string, unknown> {
  const harness = settings();
  return {
    read_only: true,
    mem_limit: spec.memory?.limit,
    memswap_limit: spec.memory?.limit,
    pids_limit: harness.pidsLimit,
    tmpfs: [`/tmp:size=${harness.tmpSize},mode=1777,exec`],
    cap_drop: ["ALL"],
    security_opt: ["no-new-privileges:true"],
  };
}

export function javaService(service: JavaService): Record<string, unknown> {
  const server = requireServer(service.state, service.spec.name);
  return {
    image: javaImage(service.state.options, service.minimumJava),
    container_name: server.container,
    labels: harnessLabels(),
    user: hostUser(),
    working_dir: CONTAINER_DATA_DIR,
    entrypoint: ["/bin/sh", "/modl-testing/run.sh"],
    environment: {
      SERVER_JAR: `/server/${basename(service.jar)}`,
      JVM_OPTS: service.jvmOptions.join(" "),
      SERVER_ARGS: service.serverArgs,
      ...(service.pterodactyl ? pterodactylEnvironment(service.spec) : {}),
      ...releaseMirrorEnvironment(service.state.options.updateFixture),
      ...service.environment,
    },
    volumes: [
      `${server.dataDir}:${CONTAINER_DATA_DIR}`,
      `${CONTAINER_ASSETS_DIR}:/modl-testing:ro`,
      `${dirname(service.jar)}:/server:ro`,
      ...identityVolumes(service.state),
      ...(service.extraVolumes ?? []),
    ],
    ports: publishedPorts(service.spec),
    stop_grace_period: "60s",
    restart: "no",
    ...(service.pterodactyl ? pterodactylConstraints(service.spec) : {}),
  };
}

export interface PaperService {
  state: EnvironmentState;
  spec: ServerSpec;
  heapMb: number;
  hostsPanel: boolean;
}

export function paperService(service: PaperService): Record<string, unknown> {
  const paper = lineOf(service.state.options).paper;
  const launch = paper.launch(service.heapMb);
  return javaService({
    state: service.state,
    spec: service.spec,
    minimumJava: service.hostsPanel ? panelHostJava(paper.java) : paper.java,
    jar: launch.jar,
    jvmOptions: launch.jvmOptions,
    serverArgs: launch.serverArgs,
    extraVolumes: launch.extraVolumes,
    pterodactyl: true,
  });
}

export function velocityJvmOptions(heapMb: number): string[] {
  return ["-Xms256M", `-Xmx${heapMb}M`, "-XX:+UseG1GC", "-Djava.awt.headless=true", "-Dterminal.jline=false", "-Dterminal.ansi=false"];
}

export function bungeecordJvmOptions(heapMb: number): string[] {
  return ["-Xms256M", `-Xmx${heapMb}M`, "-XX:+UseG1GC", "-Djava.awt.headless=true", "-Djline.terminal=jline.UnsupportedTerminal"];
}

export function backendJvmOptions(heapMb: number): string[] {
  return [`-Xmx${heapMb}M`, "-XX:+UseG1GC", "-XX:+ExitOnOutOfMemoryError"];
}

export interface ComposeInclude {
  path: string[];
  project_directory: string;
}

const TOPOLOGY_SUBNETS: Record<TopologyName, string> = {
  standalone: "10.231.10",
  network: "10.231.20",
  "network-bungee": "10.231.30",
  remote: "10.231.40",
};
const FIRST_STATIC_HOST = 10;

function withStaticAddresses(prefix: string, services: Record<string, object>): Record<string, object> {
  return Object.fromEntries(
    Object.entries(services).map(([name, service], index) => [
      name,
      { ...service, networks: { default: { ipv4_address: `${prefix}.${FIRST_STATIC_HOST + index}` } } },
    ]),
  );
}

export function renderCompose(state: EnvironmentState, services: Record<string, object>, include: ComposeInclude[] = []): string {
  const mirror = state.options.updateFixture ? { [RELEASE_MIRROR_SERVICE]: releaseMirrorService(state.project) } : {};
  const prefix = TOPOLOGY_SUBNETS[state.topology];
  writeContainerIdentity(state);
  return yamlText({
    name: state.project,
    ...(include.length > 0 ? { include } : {}),
    services: withStaticAddresses(prefix, { ...services, ...mirror }),
    networks: {
      default: {
        labels: harnessLabels(),
        ipam: { config: [{ subnet: `${prefix}.0/24`, ip_range: `${prefix}.128/25` }] },
      },
    },
  });
}
