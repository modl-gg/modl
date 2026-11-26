import { Endpoints, SetupPhases } from "../lib/contract";
import { HttpStatus, jsonField, jsonString } from "../lib/http";
import type { ScenarioContext } from "./framework";

const POLL_MS = 1000;

export async function ensureSetupFinished(t: ScenarioContext): Promise<void> {
  if ((await t.instance.setupStatus()).phase === SetupPhases.ready) return;
  await t.instance.ensureOwnerSession();
  await t.instance.completeOpenSteps();
  t.require((await t.instance.setupStatus()).phase === SetupPhases.ready, "the setup did not finish");
}

export async function latestImportStatus(t: ScenarioContext): Promise<unknown> {
  const result = await t.instance.apiWithCredentials(Endpoints.importLatest, { auth: "api-key" });
  t.require(result.status === HttpStatus.ok, `GET ${Endpoints.importLatest} returned ${result.status}`);
  return result.json;
}

export async function latestImportJob(t: ScenarioContext): Promise<unknown> {
  return jsonField(await latestImportStatus(t), "job");
}

export async function awaitImportEnded(t: ScenarioContext, jobId: string, timeoutMs: number): Promise<unknown> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const job = await latestImportJob(t);
    t.require(jsonString(job, "id") === jobId, `another import replaced ${jobId}`);
    const status = jsonString(job, "status");
    if (status === "completed" || status === "failed") return job;
    await t.sleep(POLL_MS);
  }
  return t.fail(`import ${jobId} did not end within ${timeoutMs}ms`);
}

export function punishmentData(punishment: unknown): unknown {
  return jsonField(punishment, "data");
}

export function importedIdOf(punishment: unknown): string | null {
  return jsonString(punishmentData(punishment), "importedId") ?? jsonString(punishment, "id");
}

export function isNoteOfKind(note: unknown, kind: "WARNING" | "NOTE"): boolean {
  const written = jsonString(note, "kind");
  return written === kind || written === `PLAYER_NOTE_KIND_${kind}`;
}

export function typeOrdinalOf(punishment: unknown): number {
  const ordinal = jsonField(punishment, "typeOrdinal");
  return typeof ordinal === "number" ? ordinal : 0;
}

export async function playerProfile(t: ScenarioContext, uuid: string): Promise<unknown> {
  const result = await t.instance.apiWithCredentials(Endpoints.minecraftPlayer(uuid), { auth: "api-key" });
  if (result.status !== HttpStatus.ok) return null;
  return jsonField(result.json, "profile");
}
