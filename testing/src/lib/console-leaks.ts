import { createHash } from "node:crypto";
import { statSync } from "node:fs";
import { join } from "node:path";
import { walkJar, walkJarBytes, type JarVisitor } from "./jar";
import { CACHE_DIR } from "./paths";
import { z } from "zod";
import { readJson, writeJson } from "./util";

const SERVER_PAYLOAD = /^lib\/[^/]+\.jar$/;
const NESTED_LIBRARY = /^META-INF\/(?:libraries|versions)\/.+\.jar$/;
const CLASS_FILE = /^((?:[a-z_][\w$]*\/)+)[^/]+\.class$/;
const PACKAGE_REFERENCE = /\b[a-z_][a-z0-9_]*(?:\.[a-z_][a-z0-9_]*)+/g;
const INDEX_VERSION = 5;

export interface LeakIndex {
  packages: ReadonlySet<string>;
}

function packageName(entryName: string): string | null {
  const directory = CLASS_FILE.exec(entryName)?.[1];
  if (!directory) return null;
  const segments = directory.split("/").filter(Boolean);
  if (segments.length < 2) return null;
  return segments.join(".");
}

function classesInto(target: Set<string>): JarVisitor {
  return {
    wants: (name) => CLASS_FILE.test(name),
    visit: (name) => {
      const found = packageName(name);
      if (found) target.add(found);
    },
  };
}

function nestedJars(pattern: RegExp, inner: JarVisitor): JarVisitor {
  return {
    wants: (name) => pattern.test(name),
    visit: async (name, read) => walkJarBytes(await read(), name, inner),
  };
}

function either(first: JarVisitor, second: JarVisitor): JarVisitor {
  return {
    wants: (name) => first.wants(name) || second.wants(name),
    visit: async (name, read) => (first.wants(name) ? first.visit(name, read) : second.visit(name, read)),
  };
}

interface PluginPackages {
  payload: Set<string>;
  plugin: Set<string>;
}

async function pluginPackages(pluginJar: string, payloadArchive: string): Promise<PluginPackages> {
  const packages: PluginPackages = { payload: new Set(), plugin: new Set() };
  await walkJar(payloadArchive, nestedJars(SERVER_PAYLOAD, classesInto(packages.payload)));
  await walkJar(pluginJar, classesInto(packages.plugin));
  return packages;
}

async function hostPackages(hostJars: readonly string[]): Promise<Set<string>> {
  const packages = new Set<string>();
  for (const jar of hostJars) await walkJar(jar, either(classesInto(packages), nestedJars(NESTED_LIBRARY, classesInto(packages))));
  return packages;
}

function indexKey(pluginJar: string, payloadArchive: string, hostJars: readonly string[]): string {
  const fingerprints = [pluginJar, payloadArchive, ...[...hostJars].sort()].map((jar) => {
    const file = statSync(jar);
    return `${jar}|${file.size}|${file.mtimeMs}`;
  });
  return createHash("sha256").update(`${INDEX_VERSION}:${fingerprints.join(":")}`).digest("hex").slice(0, 24);
}

const CACHED_PACKAGES = z.array(z.string()).nullable();

export async function buildLeakIndex(pluginJar: string, payloadArchive: string, hostJars: readonly string[]): Promise<LeakIndex> {
  const cacheFile = join(CACHE_DIR, "console-leaks", `${indexKey(pluginJar, payloadArchive, hostJars)}.json`);
  const cached = readJson(cacheFile, CACHED_PACKAGES, null);
  if (cached) return { packages: new Set(cached) };
  const [{ payload, plugin }, host] = await Promise.all([pluginPackages(pluginJar, payloadArchive), hostPackages(hostJars)]);
  const leaking = [...payload].filter((name) => !host.has(name) && !plugin.has(name)).sort();
  if (leaking.length === 0) throw new Error(`no server payload packages found in ${payloadArchive}; is it a modl 3 embedded archive?`);
  writeJson(cacheFile, leaking);
  return { packages: new Set(leaking) };
}

export function leakedPackages(index: LeakIndex, text: string): string[] {
  const found = new Set<string>();
  for (const match of text.matchAll(PACKAGE_REFERENCE)) {
    if (index.packages.has(match[0])) found.add(match[0]);
  }
  return [...found];
}
