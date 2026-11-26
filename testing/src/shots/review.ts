import { copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync } from "node:fs";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { join } from "node:path";
import { z } from "zod";
import { readJson, writeJson, writeText } from "../lib/util";
import type { Shot } from "./capture";
import { ImageComparer } from "./compare";
import { REVIEW_CURRENT, REVIEW_DIFF, REVIEW_FEEDBACK, REVIEW_STATE, SHOTS_BASELINE } from "./paths";
import { REVIEW_PAGE } from "./review-page";

const ENTRY_STATUS = z.enum(["changed", "new", "removed"]);
const COMMENT = z.object({ at: z.string(), text: z.string() });
const ENTRY = z.object({
  id: z.string(),
  screen: z.string(),
  variant: z.string(),
  status: ENTRY_STATUS,
  changedPixels: z.number(),
  ratio: z.number(),
  comments: z.array(COMMENT),
});
const PROBLEM = z.object({ id: z.string(), problems: z.array(z.string()) });
const STATE = z.object({
  generatedAt: z.string(),
  datasetRebuilt: z.boolean(),
  captured: z.number(),
  unchanged: z.number(),
  entries: z.array(ENTRY),
  problems: z.array(PROBLEM),
});

export type ReviewEntry = z.infer<typeof ENTRY>;
export type ReviewState = z.infer<typeof STATE>;

const EMPTY_STATE: ReviewState = { generatedAt: "", datasetRebuilt: false, captured: 0, unchanged: 0, entries: [], problems: [] };
const SHOT_FILE = /^[a-z0-9-]+\.[a-z]+-[a-z]+\.png$/;

export function readReviewState(): ReviewState {
  return readJson(REVIEW_STATE, STATE, EMPTY_STATE);
}

function writeFeedback(state: ReviewState): void {
  const sections = state.entries
    .filter((entry) => entry.comments.length > 0)
    .map((entry) => [`## ${entry.screen} (${entry.variant}, ${entry.status})`, "", ...entry.comments.map((note) => `- ${note.text.replace(/\n+/g, " ")}`), ""].join("\n"));
  writeText(REVIEW_FEEDBACK, sections.length === 0 ? "" : `# Design feedback\n\n${sections.join("\n")}`);
}

function saveState(state: ReviewState): void {
  writeJson(REVIEW_STATE, state);
  writeFeedback(state);
}

function splitId(id: string): { screen: string; variant: string } {
  const [screen = id, variant = ""] = id.replace(/\.png$/, "").split(".");
  return { screen, variant };
}

export interface ReviewOptions {
  datasetRebuilt: boolean;
  complete: boolean;
}

export async function buildReview(shots: readonly Shot[], options: ReviewOptions): Promise<ReviewState> {
  const previous = readReviewState();
  const comments = new Map(previous.entries.map((entry) => [entry.id, entry.comments]));
  const captured = new Set(shots.filter((shot) => shot.file !== "").map((shot) => shot.file.slice(REVIEW_CURRENT.length + 1)));
  const capturedProblemIds = new Set(shots.map((shot) => `${shot.screen}.${shot.variant.id}`));
  const keptEntries = options.complete ? [] : previous.entries.filter((entry) => !captured.has(entry.id) && existsSync(join(REVIEW_CURRENT, entry.id)));
  const keptProblems = options.complete ? [] : previous.problems.filter((problem) => !capturedProblemIds.has(problem.id));
  const comparer = await ImageComparer.open();
  const entries: ReviewEntry[] = [...keptEntries];
  let unchanged = 0;
  try {
    if (options.complete) rmSync(REVIEW_DIFF, { recursive: true, force: true });
    else for (const id of captured) rmSync(join(REVIEW_DIFF, id), { force: true });
    mkdirSync(REVIEW_DIFF, { recursive: true });
    for (const shot of shots) {
      if (shot.file === "" || !existsSync(shot.file)) continue;
      const id = shot.file.slice(REVIEW_CURRENT.length + 1);
      const result = await comparer.compare(join(SHOTS_BASELINE, id), shot.file, join(REVIEW_DIFF, id));
      if (result.changedPixels === 0) {
        unchanged += 1;
        continue;
      }
      const status = Number.isFinite(result.changedPixels) ? "changed" : "new";
      entries.push({ id, ...splitId(id), status, changedPixels: Number.isFinite(result.changedPixels) ? result.changedPixels : 0, ratio: result.ratio, comments: comments.get(id) ?? [] });
    }
  } finally {
    await comparer.close();
  }
  if (options.complete && existsSync(SHOTS_BASELINE)) {
    for (const id of readdirSync(SHOTS_BASELINE).filter((file) => SHOT_FILE.test(file) && !captured.has(file))) {
      entries.push({ id, ...splitId(id), status: "removed", changedPixels: 0, ratio: 0, comments: comments.get(id) ?? [] });
    }
  }
  const state: ReviewState = {
    generatedAt: new Date().toISOString(),
    datasetRebuilt: options.datasetRebuilt,
    captured: shots.filter((shot) => shot.file !== "").length,
    unchanged,
    entries: entries.sort((left, right) => left.id.localeCompare(right.id)),
    problems: [...keptProblems, ...shots.filter((shot) => shot.problems.length > 0).map((shot) => ({ id: `${shot.screen}.${shot.variant.id}`, problems: shot.problems }))],
  };
  saveState(state);
  return state;
}

export function approve(ids: readonly string[]): ReviewState {
  const state = readReviewState();
  const chosen = new Set(ids);
  mkdirSync(SHOTS_BASELINE, { recursive: true });
  const approved = new Set<string>();
  try {
    for (const entry of state.entries.filter((candidate) => chosen.has(candidate.id))) {
      if (!SHOT_FILE.test(entry.id)) continue;
      if (entry.status === "removed") rmSync(join(SHOTS_BASELINE, entry.id), { force: true });
      else copyFileSync(join(REVIEW_CURRENT, entry.id), join(SHOTS_BASELINE, entry.id));
      approved.add(entry.id);
    }
  } finally {
    saveState({ ...state, entries: state.entries.filter((entry) => !approved.has(entry.id)) });
  }
  return readReviewState();
}

export function acceptAllAsBaseline(): number {
  mkdirSync(SHOTS_BASELINE, { recursive: true });
  const files = existsSync(REVIEW_CURRENT) ? readdirSync(REVIEW_CURRENT).filter((file) => SHOT_FILE.test(file)) : [];
  for (const file of files) copyFileSync(join(REVIEW_CURRENT, file), join(SHOTS_BASELINE, file));
  saveState({ ...readReviewState(), entries: [] });
  return files.length;
}

function addComment(id: string, text: string): ReviewState {
  const state = readReviewState();
  const trimmed = text.trim();
  if (trimmed === "") return state;
  const next = {
    ...state,
    entries: state.entries.map((entry) => (entry.id === id ? { ...entry, comments: [...entry.comments, { at: new Date().toISOString(), text: trimmed }] } : entry)),
  };
  saveState(next);
  return next;
}

const APPROVE_BODY = z.object({ ids: z.array(z.string()).max(2000) });
const COMMENT_BODY = z.object({ id: z.string(), text: z.string().max(4000) });
const IMAGE_ROOTS: Record<string, string> = { baseline: SHOTS_BASELINE, current: REVIEW_CURRENT, diff: REVIEW_DIFF };
const REVIEW_HEADER = "x-modl-review";
const MAX_BODY_BYTES = 256 * 1024;

async function readBody(request: IncomingMessage): Promise<unknown> {
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of request) {
    const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(String(chunk));
    size += buffer.length;
    if (size > MAX_BODY_BYTES) throw new Error("body too large");
    chunks.push(buffer);
  }
  const parsed: unknown = JSON.parse(Buffer.concat(chunks).toString("utf8"));
  return parsed;
}

function send(response: ServerResponse, status: number, type: string, body: string | Buffer): void {
  response.writeHead(status, { "Content-Type": type, "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" });
  response.end(body);
}

function sendJson(response: ServerResponse, value: unknown): void {
  send(response, 200, "application/json", JSON.stringify(value));
}

async function route(request: IncomingMessage, response: ServerResponse, allowedHosts: ReadonlySet<string>): Promise<void> {
  if (!allowedHosts.has(request.headers.host ?? "")) {
    send(response, 421, "text/plain", "unknown host");
    return;
  }
  const url = new URL(request.url ?? "/", "http://127.0.0.1");
  if (request.method === "GET") {
    serveGet(url.pathname, response);
    return;
  }
  if (request.method === "POST" && request.headers[REVIEW_HEADER] === "1") {
    if (url.pathname === "/api/approve") {
      sendJson(response, approve(APPROVE_BODY.parse(await readBody(request)).ids));
      return;
    }
    if (url.pathname === "/api/comment") {
      const body = COMMENT_BODY.parse(await readBody(request));
      sendJson(response, addComment(body.id, body.text));
      return;
    }
  }
  send(response, 404, "text/plain", "not found");
}

function serveGet(pathname: string, response: ServerResponse): void {
  if (pathname === "/") {
    send(response, 200, "text/html; charset=utf-8", REVIEW_PAGE);
    return;
  }
  if (pathname === "/api/state") {
    sendJson(response, readReviewState());
    return;
  }
  const image = /^\/files\/(baseline|current|diff)\/([^/]+)$/.exec(pathname);
  const root = image ? IMAGE_ROOTS[image[1] ?? ""] : undefined;
  const file = image?.[2] ?? "";
  if (root === undefined || !SHOT_FILE.test(file) || !existsSync(join(root, file))) {
    send(response, 404, "text/plain", "not found");
    return;
  }
  send(response, 200, "image/png", readFileSync(join(root, file)));
}

export async function serveReview(port: number): Promise<string> {
  if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error(`--port must be a port number from 1024 to 65535`);
  const allowedHosts = new Set([`127.0.0.1:${String(port)}`, `localhost:${String(port)}`]);
  const server = createServer((request, response) => {
    route(request, response, allowedHosts).catch((error: unknown) => send(response, 400, "text/plain", String(error)));
  });
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(port, "127.0.0.1", resolve);
  });
  return `http://127.0.0.1:${String(port)}`;
}
