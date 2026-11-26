import type { LogLine } from "../../lib/logs";
import { THEME } from "../studio/theme";
import { escapeHtml, REVEAL_SCRIPT, sceneHtml, windowFrame } from "./document";

export interface ConsolePick {
  pattern: RegExp;
  highlightGroup?: number;
  name?: string;
}

export interface ConsoleLine {
  meta: string;
  text: string;
  highlight: string | null;
  name: string | null;
}

export function lineIndex(lines: readonly ConsoleLine[], name: string): number {
  const index = lines.findIndex((line) => line.name === name);
  if (index === -1) throw new Error(`no console line is named ${name}`);
  return index;
}

const PREFIX = /^(\[\d{2}:\d{2}:\d{2} [A-Z]+\]:?)\s+(.*)$/;
export function consoleLine(raw: string, pick: ConsolePick): ConsoleLine {
  const match = PREFIX.exec(raw);
  const text = match?.[2] ?? raw;
  const group = pick.highlightGroup;
  const highlight = group === undefined ? null : (pick.pattern.exec(text)?.[group] ?? null);
  if (group !== undefined && highlight === null) throw new Error(`the console line "${text}" has nothing to highlight`);
  return { meta: match?.[1] ?? "", text, highlight, name: pick.name ?? null };
}

export function consoleRange(lines: readonly LogLine[], from: RegExp, to: RegExp, names: readonly ConsolePick[]): ConsoleLine[] {
  const start = lines.findIndex((line) => from.test(line.text));
  const end = lines.findIndex((line, index) => index >= start && to.test(line.text));
  if (start === -1 || end === -1) throw new Error(`no console lines run from ${from.toString()} to ${to.toString()}`);
  return lines
    .slice(start, end + 1)
    .filter((line) => line.text.trim() !== "")
    .map((line) => consoleLine(line.text, names.find((pick) => pick.pattern.test(line.text)) ?? { pattern: /./ }));
}

export function pickConsoleLines(lines: readonly LogLine[], picks: readonly ConsolePick[]): ConsoleLine[] {
  const found = picks.map((pick) => {
    const index = lines.findIndex((line) => pick.pattern.test(line.text));
    const line = lines[index];
    if (!line) throw new Error(`no console line matches ${pick.pattern.toString()}`);
    return { index, line: consoleLine(line.text, pick) };
  });
  return found.sort((left, right) => left.index - right.index).map((entry) => entry.line);
}

const STYLE = `
.console{font:15px/1.7 ${THEME.mono};color:${THEME.foreground}}
.console .line{white-space:pre-wrap;word-break:break-word}
.console .meta{color:${THEME.muted}}
mark{background:none;color:inherit;border:1px solid transparent;border-radius:4px;padding:0 3px;margin:0 -4px;transition:color .3s ease,border-color .3s ease}
mark.on{color:${THEME.accent};border-color:${THEME.accent}}
`;

function lineHtml(line: ConsoleLine): string {
  const text = line.highlight === null ? escapeHtml(line.text) : escapeHtml(line.text).replace(escapeHtml(line.highlight), () => `<mark>${escapeHtml(line.highlight ?? "")}</mark>`);
  const meta = line.meta === "" ? "" : `<span class="meta">${escapeHtml(line.meta)}</span> `;
  const indent = line.meta === "" ? 0 : line.meta.length + 1;
  return `<div class="line reveal" style="padding-left:${indent}ch;text-indent:-${indent}ch">${meta}${text}</div>`;
}

export function consoleScene(title: string, lines: readonly ConsoleLine[]): string {
  return sceneHtml({
    style: STYLE,
    body: windowFrame(title, `<div class="console">${lines.map(lineHtml).join("")}</div>`),
    script: REVEAL_SCRIPT,
  });
}
