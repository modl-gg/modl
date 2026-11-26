import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { DIST_DIR, LITEBANS_FIXTURE_JAR, ROOT_GRADLE_PROPERTIES } from "./paths";
import { settings } from "./settings";

export function productVersion(): string {
  if (!existsSync(ROOT_GRADLE_PROPERTIES)) throw new Error(`${ROOT_GRADLE_PROPERTIES} is missing; run the harness from a modl checkout`);
  const match = /^modl\.version\s*=\s*(\S+)/m.exec(readFileSync(ROOT_GRADLE_PROPERTIES, "utf8"));
  if (!match?.[1]) throw new Error(`modl.version is missing from ${ROOT_GRADLE_PROPERTIES}`);
  return match[1];
}

export function pluginJarPath(): string {
  return settings().pluginJarOverride ?? join(DIST_DIR, `modl-${productVersion()}.jar`);
}

export function serverJarPath(): string {
  return settings().serverJarOverride ?? join(DIST_DIR, `modl-server-${productVersion()}.jar`);
}

export function embeddedArchiveName(version: string): string {
  return `modl-embedded-${version}.zip`;
}

export function embeddedArchivePath(): string {
  return settings().embeddedArchiveOverride ?? join(DIST_DIR, embeddedArchiveName(productVersion()));
}

function requireFile(path: string, description: string, buildHint = "run ./gradlew dist in the repository"): string {
  if (!existsSync(path)) {
    throw new Error(`${description} not found at ${path}; ${buildHint} first.`);
  }
  return path;
}

export function requirePluginJar(): string {
  return requireFile(pluginJarPath(), "modl plugin jar");
}

export function requireServerJar(): string {
  return requireFile(serverJarPath(), "modl server jar");
}

export function requireEmbeddedArchive(): string {
  return requireFile(embeddedArchivePath(), "modl embedded server archive");
}

export function requireLiteBansFixtureJar(): string {
  return requireFile(LITEBANS_FIXTURE_JAR, "LiteBans fixture jar", "run npm run fixture:litebans in testing/");
}
