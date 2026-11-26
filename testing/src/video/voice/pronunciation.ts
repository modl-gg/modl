import { wordCount } from "../text";

const RESPELLINGS: readonly (readonly [RegExp, string])[] = [
  [/plugins\/modl\/connection\.yml/g, "plugins slash moddle slash connection YAML"],
  [/plugins\/modl\//g, "plugins slash moddle slash "],
  [/server-features\.yml/g, "server features YAML"],
  [/connection\.yml/g, "connection YAML"],
  [/server-name/g, "server name"],
  [/\/proxycmd/g, "proxy command"],
  [/\/modl\b/g, "slash moddle"],
  [/\bmodl\.gg\b/g, "moddle G G"],
  [/\bPacketEvents\b/g, "Packet Events"],
  [/\bmodl\b/g, "moddle"],
];

export function spokenText(text: string): string {
  return RESPELLINGS.reduce((spoken, [pattern, replacement]) => spoken.replace(pattern, replacement), text);
}

export function sentencesOf(text: string): string[] {
  return text
    .split(/(?<=[.!?])\s+/)
    .map((sentence) => sentence.trim())
    .filter((sentence) => wordCount(sentence) > 0);
}

