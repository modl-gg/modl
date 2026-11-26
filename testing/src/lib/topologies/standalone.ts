import type { ArtifactId } from "../artifacts";
import { lineOf } from "../lines";
import { settings } from "../settings";
import { requireServer, type UpOptions } from "../state";
import { paperService, renderCompose } from "./compose";
import { embeddedConnectionPatches, modlConfigPatches, serverFeaturesPatches } from "./modl-config";
import { installEmbeddedServer, installPlugins, writeConnection, writeModlConfig, writePaperFiles, writeServerFeatures } from "./server-files";
import type { BootContext, BootStep, PrepareContext, ServerSpec, TopologyDefinition } from "./types";

const PAPER = "paper";
const REAL_CLIENT_PLUGINS: readonly ArtifactId[] = ["viaversion", "viabackwards"];

function realClientPlugins(options: UpOptions): readonly ArtifactId[] {
  return options.realClient ? REAL_CLIENT_PLUGINS : [];
}

function paperSpec(): ServerSpec {
  return {
    name: PAPER,
    kind: "paper",
    role: "standalone",
    service: PAPER,
    memory: settings().embeddedHost,
    ports: { game: 25700, rcon: 25750, http: 18123 },
  };
}

export const standalone: TopologyDefinition = {
  name: "standalone",
  description: "Paper + PacketEvents + modl embedded: the panel runs inside the Minecraft server",
  panelPort: 18123,
  roles: { entry: PAPER, core: PAPER, data: PAPER, notices: PAPER },
  servers: () => [paperSpec()],
  artifacts: (options) => [lineOf(options).paper.artifact, "packeteventsSpigot", ...realClientPlugins(options)],
  needsServerJar: () => false,
  expectedBootProblems: [],

  async prepare(context: PrepareContext): Promise<void> {
    const dir = requireServer(context.state, PAPER).dataDir;
    writePaperFiles(dir, {
      rconPassword: context.state.rconPassword,
      forwarding: { kind: "none" },
      motd: "modl standalone",
      world: lineOf(context.state.options).paper.worldProperties(),
    });
    await installPlugins(dir, [context.artifactPath("packeteventsSpigot"), context.state.pluginJar, ...realClientPlugins(context.state.options).map((id) => context.artifactPath(id))]);
    await installEmbeddedServer(dir, context.state.options.updateFixture);
    writeConnection(dir, context.resources.connection, embeddedConnectionPatches(context.state.options.presetAdminEmail, context.state.instance.baseUrl));
    writeModlConfig(dir, context.resources.config, modlConfigPatches(PAPER, context.state.options.updateFixture));
    writeServerFeatures(dir, context.resources.serverFeatures, serverFeaturesPatches(PAPER));
  },

  compose(context: PrepareContext): string {
    return renderCompose(context.state, {
      [PAPER]: paperService({ state: context.state, spec: paperSpec(), heapMb: settings().embeddedHost.heapMb, hostsPanel: true }),
    });
  },

  async boot(context: BootContext): Promise<BootStep[]> {
    const since = await context.mark(PAPER);
    await context.composeUp([PAPER]);
    const bootSeconds = await context.waitForMinecraft(PAPER, since, "core");
    const panelSeconds = await context.waitForPanel();
    const setupCode = await context.instance.latestSetupCode();
    return [
      { step: "paper booted with modl embedded", seconds: bootSeconds },
      { step: "panel healthy", seconds: panelSeconds, detail: { panel: context.instance.baseUrl, setupCodePrinted: setupCode !== null } },
    ];
  },

  connect: () => [],
};
