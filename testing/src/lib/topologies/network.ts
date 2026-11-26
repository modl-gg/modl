import { ARTIFACTS, type ArtifactId, type ProxyArtifactId } from "../artifacts";
import { readYamlValue, type ProxyBackend } from "../config-files";
import { ConsolePatterns } from "../contract";
import { panelHostJava } from "../java-runtime";
import { lineOf, requireVelocity, type PlatformLine } from "../lines";
import { LOG_START, type LogMark } from "../logs";
import { settings } from "../settings";
import { requireServer, type MemberDetection, type MinecraftKind, type TopologyName, type UpOptions } from "../state";
import type { Awaitable } from "../util";
import { bungeecordJvmOptions, CONTAINER_PORTS, javaService, paperService, renderCompose, velocityJvmOptions } from "./compose";
import { replaceServerKey, ServerKeyScopes } from "../server-keys";
import {
  BEHIND_PROXY_MODE,
  embeddedConnectionPatches,
  memberConnectionPatches,
  memberServerFeaturesPatches,
  modlConfigPatches,
} from "./modl-config";
import { ownerSession } from "./owner-api-key";
import {
  connectionFile,
  hasConnectionFile,
  installEmbeddedServer,
  installPlugins,
  patchConnection,
  writeBungeeFiles,
  writeConnection,
  writeModlConfig,
  writePaperFiles,
  writeServerFeatures,
  writeVelocityFiles,
  type ProxyForwarding,
} from "./server-files";
import type { BootContext, BootStep, PrepareContext, ServerSpec, TopologyDefinition } from "./types";

const PROXY = "proxy";
const MEMBERS = ["lobby", "survival"] as const;
type MemberName = (typeof MEMBERS)[number];

const MEMBER_RCON_PORTS: Record<MemberName, number> = { lobby: 25751, survival: 25752 };
const MEMBER_PANEL_URL = `http://${PROXY}:${CONTAINER_PORTS.http}`;

const FIRST_START_MEMBER_NOTICES: readonly RegExp[] = [ConsolePatterns.memberWaitsForKey];

interface ProxyFlavor {
  topology: TopologyName;
  kind: Exclude<MinecraftKind, "paper">;
  label: string;
  proxyJar(line: PlatformLine): ProxyArtifactId;
  packetEvents: ArtifactId;
  forwardingMode: string;
  forwarding(context: PrepareContext): ProxyForwarding;
  writeProxyFiles(context: PrepareContext, dir: string, proxyJar: string, backends: ProxyBackend[]): Awaitable<void>;
  jvmOptions(heapMb: number): string[];
}

const VELOCITY: ProxyFlavor = {
  topology: "network",
  kind: "velocity",
  label: "Velocity",
  proxyJar: requireVelocity,
  packetEvents: "packeteventsVelocity",
  forwardingMode: "modern forwarding",
  forwarding: (context) => ({ kind: "velocity", secret: context.state.forwardingSecret }),
  writeProxyFiles: async (context, dir, proxyJar, backends) => writeVelocityFiles(dir, { velocityJar: proxyJar, secret: context.state.forwardingSecret, backends }),
  jvmOptions: velocityJvmOptions,
};

const BUNGEECORD: ProxyFlavor = {
  topology: "network-bungee",
  kind: "bungeecord",
  label: "BungeeCord",
  proxyJar: () => "bungeecord",
  packetEvents: "packeteventsBungeecord",
  forwardingMode: "BungeeCord IP forwarding",
  forwarding: () => ({ kind: "bungeecord" }),
  writeProxyFiles: (_, dir, _proxyJar, backends) => {
    writeBungeeFiles(dir, { backends });
  },
  jvmOptions: bungeecordJvmOptions,
};

function proxySpec(flavor: ProxyFlavor): ServerSpec {
  return {
    name: PROXY,
    kind: flavor.kind,
    role: "proxy",
    service: PROXY,
    memory: settings().embeddedHost,
    ports: { game: 25701, http: 18124 },
  };
}

function memberSpec(name: MemberName): ServerSpec {
  return {
    name,
    kind: "paper",
    role: "member",
    service: name,
    memory: settings().memberHost,
    ports: { rcon: MEMBER_RCON_PORTS[name] },
  };
}

function proxyPlugins(flavor: ProxyFlavor, context: PrepareContext): string[] {
  const packetEvents = context.state.options.proxyPacketEvents ? [context.artifactPath(flavor.packetEvents)] : [];
  return [...packetEvents, context.state.pluginJar];
}

interface MemberConnection {
  mode: unknown;
  url: unknown;
  serverKey: unknown;
}

function readMemberConnection(dir: string): MemberConnection {
  const file = connectionFile(dir);
  return {
    mode: readYamlValue(file, "mode"),
    url: readYamlValue(file, "remote.url"),
    serverKey: readYamlValue(file, "remote.api-key"),
  };
}

function hasServerKey(connection: MemberConnection): boolean {
  return connection.mode === BEHIND_PROXY_MODE && connection.url === MEMBER_PANEL_URL
    && typeof connection.serverKey === "string" && connection.serverKey !== "";
}

async function detectMemberMode(context: BootContext, name: MemberName, firstStart: boolean, since: LogMark): Promise<BootStep> {
  const started = Date.now();
  await context.waitForMinecraft(name, since, "none");
  const written = readMemberConnection(requireServer(context.state, name).dataDir);
  const detection: MemberDetection = {
    server: name,
    firstStart,
    writtenMode: typeof written.mode === "string" ? written.mode : null,
    autoDetected: firstStart && written.mode === BEHIND_PROXY_MODE,
  };
  context.state.memberDetection = [...context.state.memberDetection.filter((entry) => entry.server !== name), detection];
  context.save();
  return { step: `${name} started`, seconds: (Date.now() - started) / 1000, detail: detection };
}

async function connectMember(context: BootContext, name: MemberName): Promise<BootStep> {
  const started = Date.now();
  const dir = requireServer(context.state, name).dataDir;
  if (hasServerKey(readMemberConnection(dir))) {
    await context.waitForMinecraft(name, LOG_START, "member");
    return { step: `${name} joined the modl network`, seconds: (Date.now() - started) / 1000, detail: { reconfigured: false } };
  }
  const key = await replaceServerKey(context.instance, name, ServerKeyScopes.behindProxy);
  patchConnection(dir, memberConnectionPatches(MEMBER_PANEL_URL, key.secret));
  await context.stopGracefully(name);
  const restartMark = await context.mark(name);
  await context.startExisting(name);
  await context.waitForMinecraft(name, restartMark, "member");
  return { step: `${name} joined the modl network`, seconds: (Date.now() - started) / 1000, detail: { reconfigured: true, key: key.id } };
}

function networkTopology(flavor: ProxyFlavor): TopologyDefinition {
  return {
    name: flavor.topology,
    description: `${flavor.label} with modl embedded + two Paper servers behind it (${MEMBERS.join(", ")}), ${flavor.forwardingMode}`,
    panelPort: 18124,
    roles: { entry: PROXY, core: PROXY, data: PROXY, notices: PROXY },
    servers: () => [proxySpec(flavor), ...MEMBERS.map(memberSpec)],
    artifacts: (options: UpOptions): ArtifactId[] => [
      flavor.proxyJar(lineOf(options)),
      lineOf(options).paper.artifact,
      "packeteventsSpigot",
      ...(options.proxyPacketEvents ? [flavor.packetEvents] : []),
    ],
    needsServerJar: () => false,
    expectedBootProblems: FIRST_START_MEMBER_NOTICES,

    async prepare(context: PrepareContext): Promise<void> {
      const proxyDir = requireServer(context.state, PROXY).dataDir;
      await flavor.writeProxyFiles(
        context,
        proxyDir,
        context.artifactPath(flavor.proxyJar(lineOf(context.state.options))),
        MEMBERS.map((name) => ({ name, address: `${name}:${CONTAINER_PORTS.game}` })),
      );
      await installPlugins(proxyDir, proxyPlugins(flavor, context));
      await installEmbeddedServer(proxyDir, context.state.options.updateFixture);
      writeConnection(proxyDir, context.resources.connection,
        embeddedConnectionPatches(context.state.options.presetAdminEmail, context.state.instance.baseUrl));
      writeModlConfig(proxyDir, context.resources.config, modlConfigPatches(PROXY, context.state.options.updateFixture));
      for (const name of MEMBERS) {
        const dir = requireServer(context.state, name).dataDir;
        writePaperFiles(dir, {
          rconPassword: context.state.rconPassword,
          forwarding: flavor.forwarding(context),
          motd: `modl ${name}`,
          world: lineOf(context.state.options).paper.worldProperties(),
        });
        await installPlugins(dir, [context.artifactPath("packeteventsSpigot"), context.state.pluginJar]);
        writeServerFeatures(dir, context.resources.serverFeatures, memberServerFeaturesPatches(name));
      }
    },

    compose(context: PrepareContext): string {
      const proxyJar = flavor.proxyJar(lineOf(context.state.options));
      const services: Record<string, object> = {
        [PROXY]: javaService({
          state: context.state,
          spec: proxySpec(flavor),
          minimumJava: panelHostJava(ARTIFACTS[proxyJar].java),
          jar: context.artifactPath(proxyJar),
          jvmOptions: flavor.jvmOptions(settings().embeddedHost.heapMb),
          serverArgs: "",
          pterodactyl: true,
        }),
      };
      for (const name of MEMBERS) {
        services[name] = paperService({ state: context.state, spec: memberSpec(name), heapMb: settings().memberHost.heapMb, hostsPanel: false });
      }
      return renderCompose(context.state, services);
    },

    async boot(context: BootContext): Promise<BootStep[]> {
      const firstStarts = MEMBERS.map((name) => ({ name, firstStart: !hasConnectionFile(requireServer(context.state, name).dataDir) }));
      const proxySince = await context.mark(PROXY);
      const members = await Promise.all(firstStarts.map(async (member) => ({ ...member, since: await context.mark(member.name) })));
      await context.composeUp([PROXY, ...MEMBERS]);
      const proxySeconds = await context.waitForMinecraft(PROXY, proxySince, "core");
      const panelSeconds = await context.waitForPanel();
      const memberSteps = await Promise.all(members.map(async (member) => detectMemberMode(context, member.name, member.firstStart, member.since)));
      return [
        { step: `${flavor.label} booted with modl embedded`, seconds: proxySeconds },
        { step: "panel healthy", seconds: panelSeconds, detail: { panel: context.instance.baseUrl } },
        ...memberSteps,
      ];
    },

    async connect(context: BootContext): Promise<BootStep[]> {
      const owner = await ownerSession(context);
      const memberSteps = await Promise.all(MEMBERS.map(async (name) => connectMember(context, name)));
      return [owner, ...memberSteps];
    },
  };
}

export const network = networkTopology(VELOCITY);
export const networkBungee = networkTopology(BUNGEECORD);
