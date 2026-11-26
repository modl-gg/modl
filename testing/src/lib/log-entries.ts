import type { LogLine } from "./logs";

export type EntryLevel = "TRACE" | "DEBUG" | "INFO" | "WARN" | "ERROR" | "FATAL";

export interface ConsoleEntry {
  ts: string;
  level: EntryLevel;
  lines: string[];
}

const ENTRY_HEADER = /^\s*\[?(?:\d{4}-\d{2}-\d{2}[ T])?\d{2}:\d{2}:\d{2}/;
const LEVEL_TOKEN = /(?:^|[\s[/])(TRACE|DEBUG|INFO|WARN|WARNING|ERROR|SEVERE|FATAL)(?=[\]\s:])/;
const BARE_STDERR_HEADER = /^(WARNING|WARN|ERROR|SEVERE)\b:?/;

function normalizeLevel(token: string | undefined): EntryLevel {
  switch (token) {
    case "WARNING":
      return "WARN";
    case "SEVERE":
      return "ERROR";
    case "TRACE":
    case "DEBUG":
    case "WARN":
    case "ERROR":
    case "FATAL":
      return token;
    case undefined:
    default:
      return "INFO";
  }
}

function headerLevel(text: string): EntryLevel {
  return normalizeLevel(LEVEL_TOKEN.exec(text.slice(0, 96))?.[1]);
}

export function parseEntries(lines: readonly LogLine[]): ConsoleEntry[] {
  const entries: ConsoleEntry[] = [];
  for (const line of lines) {
    const current = entries.at(-1);
    if (ENTRY_HEADER.test(line.text)) {
      entries.push({ ts: line.ts, level: headerLevel(line.text), lines: [line.text] });
    } else if (BARE_STDERR_HEADER.test(line.text) || !current) {
      entries.push({ ts: line.ts, level: normalizeLevel(BARE_STDERR_HEADER.exec(line.text)?.[1]), lines: [line.text] });
    } else if (line.text.length > 0) {
      current.lines.push(line.text);
    }
  }
  return entries;
}

export function entryText(entry: ConsoleEntry): string {
  return entry.lines.join("\n");
}
