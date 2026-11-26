import { existsSync, mkdirSync, readdirSync, rmSync } from "node:fs";
import { basename, join } from "node:path";
import { patchYamlFile, renderVelocityToml, writeProperties, type ProxyBackend, type YamlPatches } from "../config-files";
import { readJarEntry } from "../jar";
import { replaceSharedFile, replaceSharedFiles } from "../shared-files";
import { embeddedArchiveName, productVersion, requireEmbeddedArchive } from "../product";
import type { WorldProperties } from "../paper";
import { writeText } from "../util";
import type { PluginResources } from "./types";

export const MODL_FOLDER = join("plugins", "modl");

export function modlDir(serverDir: string): string {
  return join(serverDir, MODL_FOLDER);
}

export function pluginLibraryDir(serverDir: string): string {
  return join(modlDir(serverDir), "lib");
}

export function embeddedServerDir(serverDir: string): string {
  return join(modlDir(serverDir), "server");
}

export function embeddedArchiveFile(serverDir: string): string {
  return join(embeddedServerDir(serverDir), embeddedArchiveName(productVersion()));
}

const EMBEDDED_ARCHIVE = /^modl-embedded-.+\.zip$/;

export function removeEmbeddedArchives(serverDir: string): void {
  const dir = embeddedServerDir(serverDir);
  if (!existsSync(dir)) return;
  for (const file of readdirSync(dir)) {
    if (EMBEDDED_ARCHIVE.test(file)) rmSync(join(dir, file), { force: true });
  }
}

export async function installEmbeddedServer(serverDir: string, downloadsFromMirror: boolean): Promise<void> {
  removeEmbeddedArchives(serverDir);
  if (downloadsFromMirror) return;
  mkdirSync(embeddedServerDir(serverDir), { recursive: true });
  await replaceSharedFile(requireEmbeddedArchive(), embeddedArchiveFile(serverDir));
}

export function connectionFile(serverDir: string): string {
  return join(modlDir(serverDir), "connection.yml");
}

async function packagedResource(pluginJar: string, entry: string): Promise<string> {
  const content = await readJarEntry(pluginJar, entry);
  if (content === null) throw new Error(`${pluginJar} has no ${entry}; rebuild the plugin with ./gradlew dist`);
  return content;
}

export async function loadPluginResources(pluginJar: string): Promise<PluginResources> {
  const [connection, config, serverFeatures] = await Promise.all([
    packagedResource(pluginJar, "connection.yml"),
    packagedResource(pluginJar, "config.yml"),
    packagedResource(pluginJar, "server-features.yml"),
  ]);
  return { connection, config, serverFeatures };
}

export function writeConnection(serverDir: string, seed: string, patches: YamlPatches): void {
  patchYamlFile(connectionFile(serverDir), patches, seed);
}

export function patchConnection(serverDir: string, patches: YamlPatches): void {
  const file = connectionFile(serverDir);
  if (!existsSync(file)) throw new Error(`${file} does not exist yet; the plugin writes it on its first start`);
  patchYamlFile(file, patches);
}

export function writeModlConfig(serverDir: string, seed: string, patches: YamlPatches): void {
  patchYamlFile(join(modlDir(serverDir), "config.yml"), patches, seed);
}

export function writeServerFeatures(serverDir: string, seed: string, patches: YamlPatches): void {
  patchYamlFile(join(modlDir(serverDir), "server-features.yml"), patches, seed);
}

export async function installPlugins(serverDir: string, jars: readonly string[]): Promise<void> {
  const pluginsDir = join(serverDir, "plugins");
  mkdirSync(pluginsDir, { recursive: true });
  for (const file of readdirSync(pluginsDir)) {
    if (file.endsWith(".jar")) rmSync(join(pluginsDir, file), { force: true });
  }
  await replaceSharedFiles(pluginsDir, jars.map((jar) => [jar, basename(jar)] as const));
}

export type ProxyForwarding = { kind: "none" } | { kind: "velocity"; secret: string } | { kind: "bungeecord" };

export interface PaperFiles {
  rconPassword: string;
  forwarding: ProxyForwarding;
  motd: string;
  world: WorldProperties;
}

export function writePaperFiles(serverDir: string, files: PaperFiles): void {
  mkdirSync(serverDir, { recursive: true });
  writeText(join(serverDir, "eula.txt"), "eula=true\n");
  writeProperties(join(serverDir, "server.properties"), {
    "online-mode": "false",
    "enforce-secure-profile": "false",
    "prevent-proxy-connections": "false",
    "server-ip": "",
    "server-port": "25565",
    "enable-rcon": "true",
    "rcon.port": "25575",
    "rcon.password": files.rconPassword,
    "broadcast-rcon-to-ops": "false",
    "broadcast-console-to-ops": "false",
    ...files.world,
    "generate-structures": "false",
    "spawn-protection": "0",
    "view-distance": "4",
    "simulation-distance": "4",
    "sync-chunk-writes": "false",
    "max-players": "40",
    "allow-flight": "true",
    "white-list": "false",
    motd: files.motd,
  });
  patchYamlFile(join(serverDir, "bukkit.yml"), { "settings.connection-throttle": -1 });
  patchYamlFile(join(serverDir, "spigot.yml"), { "settings.bungeecord": files.forwarding.kind === "bungeecord" });
  patchYamlFile(join(serverDir, "config", "paper-global.yml"), {
    "proxies.bungee-cord.online-mode": false,
    "proxies.velocity.enabled": files.forwarding.kind === "velocity",
    "proxies.velocity.online-mode": false,
    "proxies.velocity.secret": files.forwarding.kind === "velocity" ? files.forwarding.secret : "",
  });
}

export interface VelocityFiles {
  velocityJar: string;
  secret: string;
  backends: ProxyBackend[];
}

export async function writeVelocityFiles(serverDir: string, files: VelocityFiles): Promise<void> {
  mkdirSync(serverDir, { recursive: true });
  const defaults = await readJarEntry(files.velocityJar, "default-velocity.toml");
  if (!defaults) throw new Error(`${files.velocityJar} has no default-velocity.toml`);
  const toml = renderVelocityToml(
    defaults,
    {
      bind: '"0.0.0.0:25565"',
      motd: '"<gray>modl testing network"',
      "online-mode": "false",
      "force-key-authentication": "false",
      "player-info-forwarding-mode": '"modern"',
      "forwarding-secret-file": '"forwarding.secret"',
      "login-ratelimit": "0",
    },
    files.backends,
  );
  writeText(join(serverDir, "velocity.toml"), toml);
  writeText(join(serverDir, "forwarding.secret"), files.secret);
}

export interface BungeeFiles {
  backends: ProxyBackend[];
}

export function writeBungeeFiles(serverDir: string, files: BungeeFiles): void {
  mkdirSync(serverDir, { recursive: true });
  const servers = Object.fromEntries(
    files.backends.map((backend) => [backend.name, { motd: `modl ${backend.name}`, address: backend.address, restricted: false }]),
  );
  patchYamlFile(join(serverDir, "config.yml"), {
    listeners: [
      {
        host: "0.0.0.0:25565",
        query_port: 25577,
        query_enabled: false,
        motd: "&7modl testing network",
        max_players: 40,
        tab_list: "GLOBAL_PING",
        tab_size: 60,
        forced_hosts: {},
        priorities: files.backends.map((backend) => backend.name),
        force_default_server: false,
        bind_local_address: true,
        ping_passthrough: false,
        proxy_protocol: false,
      },
    ],
    servers,
    ip_forward: true,
    online_mode: false,
    enforce_secure_profile: false,
    connection_throttle: -1,
    log_pings: false,
    log_commands: false,
    prevent_proxy_connections: false,
    groups: {},
  });
}

export function hasConnectionFile(serverDir: string): boolean {
  return existsSync(connectionFile(serverDir));
}
