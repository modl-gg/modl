import { randomUUID } from "node:crypto";
import { existsSync, mkdirSync, readdirSync, rmSync } from "node:fs";
import { basename, join } from "node:path";
import { artifactPath, type ArtifactId } from "../lib/artifacts";
import { patchYamlFile, renderVelocityToml, writeProperties, type ProxyBackend } from "../lib/config-files";
import { readJarEntry } from "../lib/jar";
import { replaceSharedFiles } from "../lib/shared-files";
import { memberConnectionPatches, remoteConnectionPatches, serverFeaturesPatches } from "../lib/topologies/modl-config";
import {
  connectionFile,
  embeddedServerDir,
  installEmbeddedServer,
  writeConnection,
  writeModlConfig,
  writeServerFeatures,
} from "../lib/topologies/server-files";
import type { PluginResources } from "../lib/topologies/types";
import { writeText } from "../lib/util";
import { BACKEND_NAMES, devServerDir, MINECRAFT_PORT, type BackendName, type DevServerName } from "./layout";

const BACKENDS: readonly ProxyBackend[] = BACKEND_NAMES.map((name) => ({ name, address: `${name}:${MINECRAFT_PORT}` }));
const DEFAULT_SERVER: BackendName = "latest";

const MANAGED_PLUGIN = /^(?:modl|packetevents|ViaVersion|ViaBackwards|ViaRewind)-.+\.jar$/;

const PROXY_PLUGINS: readonly ArtifactId[] = ["packeteventsVelocity", "viaversion", "viabackwards", "viarewind"];
const BACKEND_PLUGINS: readonly ArtifactId[] = ["packeteventsSpigot"];

export const DEV_ARTIFACTS: readonly ArtifactId[] = ["velocityLatest", "paperLatest", "paperLegacy", ...PROXY_PLUGINS, ...BACKEND_PLUGINS];

export function panelUrlInside(panelPort: number): string {
  return `http://proxy:${panelPort}`;
}

export function panelUrlOnHost(panelPort: number): string {
  return `http://localhost:${panelPort}`;
}

export interface SeedContext {
  resources: PluginResources;
  forwardingSecret: string;
  panelPort: number;
}

interface VelocityProfile {
  motd: string;
  onlineMode: boolean;
}

const VELOCITY_PROFILES: Record<"proxy" | "botgate", VelocityProfile> = {
  proxy: { motd: "<aqua>modl dev network", onlineMode: true },
  botgate: { motd: "<aqua>modl dev network <dark_gray>(bot gate)", onlineMode: false },
};

const VIA_PROTOCOLS: Record<BackendName, number> = { latest: 775, legacy: 47 };

function writeProxyPluginConfigs(dir: string): void {
  patchYamlFile(join(dir, "plugins", "viaversion", "config.yml"), { "velocity-servers": { ...VIA_PROTOCOLS } });
  writeText(join(dir, "plugins", "bStats", "config.txt"), `enabled=false\nserver-uuid=${randomUUID()}\nlog-errors=false\nlog-sent-data=false\nlog-response-status-text=false\n`);
}

function writeBackendPluginConfigs(dir: string): void {
  patchYamlFile(join(dir, "plugins", "bStats", "config.yml"), { enabled: false });
}

async function writeVelocityConfig(dir: string, profile: VelocityProfile, secret: string): Promise<void> {
  const velocityJar = artifactPath("velocityLatest");
  const defaults = await readJarEntry(velocityJar, "default-velocity.toml");
  if (!defaults) throw new Error(`${velocityJar} has no default-velocity.toml`);
  const toml = renderVelocityToml(
    defaults,
    {
      bind: `"0.0.0.0:${MINECRAFT_PORT}"`,
      motd: `"${profile.motd}"`,
      "show-max-players": "100",
      "online-mode": String(profile.onlineMode),
      "force-key-authentication": String(profile.onlineMode),
      "player-info-forwarding-mode": '"legacy"',
      "forwarding-secret-file": '"forwarding.secret"',
      "login-ratelimit": profile.onlineMode ? "3000" : "0",
    },
    BACKENDS,
    [DEFAULT_SERVER],
  );
  writeText(join(dir, "velocity.toml"), toml);
  writeText(join(dir, "forwarding.secret"), secret);
  writeProxyPluginConfigs(dir);
}

interface BackendProfile {
  motd: string;
  modern: boolean;
}

const BACKEND_PROFILES: Record<BackendName, BackendProfile> = {
  latest: { motd: "modl dev latest", modern: true },
  legacy: { motd: "modl dev legacy", modern: false },
};

function writeBackendConfig(dir: string, profile: BackendProfile): void {
  mkdirSync(dir, { recursive: true });
  writeText(join(dir, "eula.txt"), "eula=true\n");
  const modernOnly = profile.modern
    ? { difficulty: "normal", "simulation-distance": "8", "enforce-secure-profile": "false" }
    : { difficulty: "2" };
  writeProperties(join(dir, "server.properties"), {
    "online-mode": "false",
    "prevent-proxy-connections": "false",
    "server-ip": "",
    "server-port": String(MINECRAFT_PORT),
    "enable-rcon": "false",
    "view-distance": "8",
    "spawn-protection": "16",
    "max-players": "50",
    "white-list": "false",
    "allow-flight": "false",
    pvp: "true",
    "generate-structures": "true",
    motd: profile.motd,
    ...modernOnly,
  });
  patchYamlFile(join(dir, "bukkit.yml"), { "settings.connection-throttle": -1 });
  patchYamlFile(join(dir, "spigot.yml"), { "settings.bungeecord": true });
  writeBackendPluginConfigs(dir);
  if (profile.modern) {
    patchYamlFile(join(dir, "config", "paper-global.yml"), {
      "proxies.bungee-cord.online-mode": true,
      "proxies.velocity.enabled": false,
    });
  }
}

export async function seedServer(name: DevServerName, context: SeedContext): Promise<void> {
  const dir = devServerDir(name);
  mkdirSync(dir, { recursive: true });
  switch (name) {
    case "proxy":
      await writeVelocityConfig(dir, VELOCITY_PROFILES.proxy, context.forwardingSecret);
      writeConnection(dir, context.resources.connection, {
        mode: "embedded",
        "embedded.port": context.panelPort,
        "embedded.bind-address": "0.0.0.0",
        "embedded.public-url": panelUrlOnHost(context.panelPort),
        "embedded.admin-email": "",
      });
      writeModlConfig(dir, context.resources.config, { "server.name": "proxy" });
      mkdirSync(join(embeddedServerDir(dir), "logs"), { recursive: true });
      return;
    case "botgate":
      await writeVelocityConfig(dir, VELOCITY_PROFILES.botgate, context.forwardingSecret);
      writeModlConfig(dir, context.resources.config, { "server.name": "botgate" });
      return;
    case "latest":
    case "legacy":
      writeBackendConfig(dir, BACKEND_PROFILES[name]);
      writeModlConfig(dir, context.resources.config, { "server.name": name });
      writeServerFeatures(dir, context.resources.serverFeatures, serverFeaturesPatches(name));
      return;
  }
}

function pluginArtifacts(name: DevServerName): readonly ArtifactId[] {
  switch (name) {
    case "proxy":
    case "botgate":
      return PROXY_PLUGINS;
    case "latest":
    case "legacy":
      return BACKEND_PLUGINS;
  }
}

export async function installPlugins(name: DevServerName, pluginJar: string): Promise<void> {
  const pluginsDir = join(devServerDir(name), "plugins");
  mkdirSync(pluginsDir, { recursive: true });
  for (const file of readdirSync(pluginsDir)) {
    if (MANAGED_PLUGIN.test(file)) rmSync(join(pluginsDir, file), { force: true });
  }
  const jars = [...pluginArtifacts(name).map(artifactPath), pluginJar];
  await replaceSharedFiles(pluginsDir, jars.map((jar) => [jar, basename(jar)] as const));
  if (name === "proxy") await installEmbeddedServer(devServerDir(name), false);
}

export function writeMemberKey(name: BackendName, panelPort: number, serverKey: string, resources: PluginResources): void {
  writeConnection(devServerDir(name), resources.connection, memberConnectionPatches(panelUrlInside(panelPort), serverKey));
}

export function writeBotGateKey(panelPort: number, serverKey: string, resources: PluginResources): void {
  writeConnection(devServerDir("botgate"), resources.connection, remoteConnectionPatches(panelUrlInside(panelPort), serverKey));
}

export function hasConnectionFile(name: DevServerName): boolean {
  return existsSync(connectionFile(devServerDir(name)));
}
