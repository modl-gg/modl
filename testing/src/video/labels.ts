import { readFileSync } from "node:fs";
import { join } from "node:path";
import { REPO_ROOT } from "../lib/paths";
import { wordCount } from "./text";

const WIKI_DIR = join(REPO_ROOT, "wiki");
const MAX_LABEL_WORDS = 6;
const VERSION_PLACEHOLDER = "<version>";

export interface StepLabel {
  page: string;
  text: string;
}

function normalized(text: string): string {
  return text
    .replace(/\[([^\]]*)\]\([^)]*\)/g, "$1")
    .replace(/`/g, "")
    .replace(/\s+/g, " ")
    .toLowerCase()
    .trim();
}

function wikiPagePath(page: string): string {
  return join(WIKI_DIR, `${page}.md`);
}

function containsPhrase(text: string, phrase: string): boolean {
  const escaped = phrase.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return new RegExp(`(?<![\\p{L}\\p{N}])${escaped}(?![\\p{L}\\p{N}])`, "u").test(text);
}

export function labelProblems(labels: readonly StepLabel[]): string[] {
  const pages = new Map<string, string>();
  const pageText = (page: string): string => {
    const cached = pages.get(page);
    if (cached !== undefined) return cached;
    const text = normalized(readFileSync(wikiPagePath(page), "utf8"));
    pages.set(page, text);
    return text;
  };
  return labels.flatMap((label) => {
    const problems: string[] = [];
    if (wordCount(label.text) > MAX_LABEL_WORDS) problems.push(`"${label.text}" has more than ${MAX_LABEL_WORDS} words`);
    if (!containsPhrase(pageText(label.page), normalized(label.text))) problems.push(`"${label.text}" is not word for word in wiki/${label.page}.md`);
    return problems;
  });
}

export function labelText(label: StepLabel, version: string): string {
  const text = label.text.replace(VERSION_PLACEHOLDER, version);
  return `${text.charAt(0).toUpperCase()}${text.slice(1)}`;
}
