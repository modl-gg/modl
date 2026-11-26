import { existsSync, readFileSync } from "node:fs";
import { Document, parseDocument, stringify } from "yaml";
import { writeText } from "./util";

export type YamlPatches = Record<string, unknown>;

export function patchYamlFile(file: string, patches: YamlPatches, seed = "", removals: readonly string[] = []): void {
  const source = existsSync(file) ? readFileSync(file, "utf8") : seed;
  const parsed = parseDocument(source);
  const document: Document = parsed.contents === null ? new Document({}) : parsed;
  for (const [dottedKey, value] of Object.entries(patches)) document.setIn(dottedKey.split("."), value);
  for (const dottedKey of removals) document.deleteIn(dottedKey.split("."));
  writeText(file, document.toString());
}

export function readYamlValue(file: string, dottedKey: string): unknown {
  if (!existsSync(file)) return undefined;
  return parseDocument(readFileSync(file, "utf8")).getIn(dottedKey.split("."));
}

export function yamlText(value: unknown): string {
  return stringify(value);
}

export function writeProperties(file: string, properties: Record<string, string>): void {
  const merged = new Map<string, string>();
  if (existsSync(file)) {
    for (const line of readFileSync(file, "utf8").split("\n")) {
      const separator = line.indexOf("=");
      if (separator > 0 && !line.startsWith("#")) merged.set(line.slice(0, separator), line.slice(separator + 1));
    }
  }
  for (const [key, value] of Object.entries(properties)) merged.set(key, value);
  writeText(file, `${[...merged].map(([key, value]) => `${key}=${value}`).join("\n")}\n`);
}

export interface ProxyBackend {
  name: string;
  address: string;
}

export function renderVelocityToml(
  defaults: string,
  settings: Record<string, string>,
  backends: readonly ProxyBackend[],
  tryOrder: readonly string[] = backends.map((backend) => backend.name),
): string {
  let toml = defaults;
  for (const [key, value] of Object.entries(settings)) {
    const line = new RegExp(`^${key} = .*$`, "m");
    if (!line.test(toml)) throw new Error(`velocity.toml has no key ${key}`);
    toml = toml.replace(line, `${key} = ${value}`);
  }
  const servers = backends.map((backend) => `${backend.name} = "${backend.address}"`).join("\n");
  const tryList = tryOrder.map((name) => `"${name}"`).join(", ");
  const section = /\[servers\][\s\S]*?(?=\n\[advanced\])/;
  if (!section.test(toml)) throw new Error("velocity.toml has no [servers] section before [advanced]");
  return toml.replace(section, `[servers]\n${servers}\ntry = [${tryList}]\n\n[forced-hosts]\n`);
}
