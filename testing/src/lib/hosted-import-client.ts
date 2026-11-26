import { HostedImportEndpoints, HostedImportStatuses } from "./contract";
import { HttpStatus, jsonArray, jsonField, jsonString, retryAfterSeconds, type HttpRequest, type HttpResult } from "./http";
import { InstanceError, type InstanceClient } from "./instance";
import { errorMessage, sleep } from "./util";

const POLL_MS = 1000;
const DEFAULT_RETRY_SECONDS = 60;
const RATE_LIMIT_RETRIES = 4;
const REQUEST_TIMEOUT_MS = 60_000;

export interface HostedImportState {
  status: string;
  importId: string;
  phase: string;
  eligible: boolean;
  blockers: string[];
  receivedBytes: number;
  phaseDone: number;
  reasonCode: string;
}

function parseState(json: unknown): HostedImportState {
  return {
    status: jsonString(json, "status") ?? "",
    importId: jsonString(json, "importId") ?? "",
    phase: jsonString(json, "phase") ?? "",
    eligible: jsonField(json, "eligible") === true,
    blockers: jsonArray(json, "blockers").filter((blocker): blocker is string => typeof blocker === "string"),
    receivedBytes: Number(jsonField(json, "receivedBytes") ?? 0),
    phaseDone: Number(jsonField(json, "phaseDone") ?? 0),
    reasonCode: jsonString(json, "reasonCode") ?? "",
  };
}

function requireOk(action: string, result: HttpResult): HttpResult {
  if (result.status !== HttpStatus.ok) throw new InstanceError(`${action} failed`, result);
  return result;
}

export class HostedImportClient {
  private readonly instance: InstanceClient;

  constructor(instance: InstanceClient) {
    this.instance = instance;
  }

  async request(path: string, request: HttpRequest = {}): Promise<HttpResult> {
    for (let attempt = 0; ; attempt += 1) {
      const result = await this.instance.api(path, { timeoutMs: REQUEST_TIMEOUT_MS, ...request });
      if (result.status !== HttpStatus.tooManyRequests || attempt >= RATE_LIMIT_RETRIES) return result;
      await sleep(((retryAfterSeconds(result) ?? DEFAULT_RETRY_SECONDS) + 1) * 1000);
    }
  }

  async state(): Promise<HostedImportState> {
    return parseState(requireOk("reading the import state", await this.request(HostedImportEndpoints.state)).json);
  }

  async begin(archive: Buffer): Promise<HostedImportState> {
    const json = { sizeBytes: String(archive.length), fileName: "modl-export.zip" };
    const result = await this.request(HostedImportEndpoints.upload, { method: "POST", json });
    return parseState(requireOk("beginning the upload", result).json);
  }

  async chunk(importId: string, offset: number, octets: Buffer): Promise<HttpResult> {
    return this.request(HostedImportEndpoints.uploadChunk(importId, offset), { method: "PUT", octets });
  }

  async upload(importId: string, archive: Buffer, from: number, until: number, chunkBytes: number): Promise<number> {
    let offset = from;
    while (offset < until) {
      const result = await this.chunk(importId, offset, archive.subarray(offset, Math.min(until, offset + chunkBytes)));
      offset = parseState(requireOk(`uploading at byte ${offset}`, result).json).receivedBytes;
    }
    return offset;
  }

  async completeUpload(importId: string): Promise<void> {
    requireOk("completing the upload", await this.request(HostedImportEndpoints.uploadComplete, { method: "POST", json: { importId } }));
  }

  async awaitState(wanted: (state: HostedImportState) => boolean, timeoutMs: number): Promise<HostedImportState> {
    const deadline = Date.now() + timeoutMs;
    let last = "no state was read";
    while (Date.now() < deadline) {
      try {
        const state = await this.state();
        if (wanted(state)) return state;
        last = JSON.stringify(state);
      } catch (error) {
        last = errorMessage(error);
      }
      await sleep(POLL_MS);
    }
    throw new InstanceError(`the import did not reach the expected state within ${timeoutMs}ms: ${last}`);
  }

  async awaitVerified(timeoutMs: number): Promise<HostedImportState> {
    return this.awaitState((state) => state.status !== HostedImportStatuses.verifying, timeoutMs);
  }
}
