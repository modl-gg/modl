import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";

export interface ClientOptions {
  readonly guiScale: number;
  readonly forceUnicodeFont: boolean;
}

const EXPECTED: Readonly<Record<string, string>> = {
  chatLinks: "true",
  chatLinksPrompt: "true",
  chatScale: "1.0",
  chatLineSpacing: "0.0",
  "key_key.chat": "key.keyboard.t",
  "key_key.command": "key.keyboard.slash",
};
const MIN_GUI = { width: 320, height: 240 } as const;

function parse(text: string): Map<string, string> {
  const entries = new Map<string, string>();
  for (const line of text.split("\n")) {
    const separator = line.indexOf(":");
    if (separator > 0) entries.set(line.slice(0, separator), line.slice(separator + 1).trim());
  }
  return entries;
}

export function readClientOptions(gameDir: string): ClientOptions {
  const file = join(gameDir, "options.txt");
  if (!existsSync(file)) throw new Error(`${file} is missing; start the client once so it writes its options`);
  const entries = parse(readFileSync(file, "utf8"));
  const problems = Object.entries(EXPECTED).flatMap(([key, value]) => {
    const actual = entries.get(key);
    return actual === undefined || actual === value ? [] : [`${key} is ${actual} (the videos need ${value})`];
  });
  if (problems.length > 0) throw new Error(`the Minecraft client options do not match what the videos click: ${problems.join(", ")}`);
  return { guiScale: Number(entries.get("guiScale") ?? "0"), forceUnicodeFont: entries.get("forceUnicodeFont") === "true" };
}

export function effectiveGuiScale(options: ClientOptions, width: number, height: number): number {
  let scale = 1;
  while (scale !== options.guiScale && scale < width && scale < height && Math.floor(width / (scale + 1)) >= MIN_GUI.width && Math.floor(height / (scale + 1)) >= MIN_GUI.height) scale += 1;
  return options.forceUnicodeFont && scale % 2 !== 0 ? scale + 1 : scale;
}
