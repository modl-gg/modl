import { z } from "zod";
import { requestSignal } from "./cancellation";

export const HTTP_METHOD = z.enum(["GET", "HEAD", "POST", "PUT", "PATCH", "DELETE"]);
export type HttpMethod = z.infer<typeof HTTP_METHOD>;

export const HTTP_AUTH = z.enum(["session", "api-key", "none"]);
export type HttpAuth = z.infer<typeof HTTP_AUTH>;

export interface HttpRequest {
  method?: HttpMethod;
  json?: unknown;
  octets?: Buffer;
  auth?: HttpAuth;
  headers?: Record<string, string>;
  timeoutMs?: number;
}

export interface HttpResult {
  method: HttpMethod;
  path: string;
  status: number;
  contentType: string;
  text: string;
  json: unknown;
  setCookies: string[];
}

export interface ByteRequest {
  auth?: "session" | "none";
  range?: string;
}

export interface ByteResult {
  status: number;
  contentType: string;
  cacheControl: string;
  contentRange: string;
  body: Buffer;
}

export const HttpStatus = {
  ok: 200,
  created: 201,
  accepted: 202,
  noContent: 204,
  badRequest: 400,
  partialContent: 206,
  unauthorized: 401,
  forbidden: 403,
  notFound: 404,
  conflict: 409,
  tooManyRequests: 429,
} as const;

const STATE_CHANGING = new Set<HttpMethod>(["POST", "PUT", "PATCH", "DELETE"]);

const PANEL_API_PREFIX = "/v1/panel/";
const SESSION_KEY_HEADER = "X-Modl-Session-Key";
const SESSION_KEY_FIELD = "sessionKey";

export interface SessionCredentials {
  cookies: Record<string, string>;
  sessionKey: string | null;
}

const NO_CREDENTIALS: SessionCredentials = { cookies: {}, sessionKey: null };

export class HttpSession {
  private readonly cookies = new Map<string, string>();
  private sessionKey: string | null;
  readonly baseUrl: string;
  private readonly apiKey: () => string | null;

  constructor(baseUrl: string, apiKey: () => string | null = () => null, initial: SessionCredentials = NO_CREDENTIALS) {
    this.baseUrl = baseUrl;
    this.apiKey = apiKey;
    for (const [name, value] of Object.entries(initial.cookies)) this.cookies.set(name, value);
    this.sessionKey = initial.sessionKey;
  }

  credentials(): SessionCredentials {
    return { cookies: Object.fromEntries(this.cookies), sessionKey: this.sessionKey };
  }

  hasSession(): boolean {
    return this.cookies.size > 0;
  }

  clearSession(): void {
    this.cookies.clear();
    this.sessionKey = null;
  }

  absolute(path: string): string {
    return this.resolve(path).toString();
  }

  private resolve(path: string): URL {
    return new URL(path, `${this.baseUrl}/`);
  }

  async request(path: string, request: HttpRequest = {}): Promise<HttpResult> {
    const method = request.method ?? "GET";
    const auth = request.auth ?? "session";
    const url = this.resolve(path);
    if (auth !== "none" && url.origin !== new URL(this.baseUrl).origin) {
      throw new Error(`refusing to send instance credentials to ${url.origin}; use auth none for other hosts`);
    }
    const headers: Record<string, string> = { Accept: "application/json", ...request.headers };
    if (request.json !== undefined) headers["Content-Type"] ??= "application/json";
    if (request.octets !== undefined) headers["Content-Type"] ??= "application/octet-stream";
    if (STATE_CHANGING.has(method)) headers["Origin"] = this.baseUrl;
    if (auth === "session" && this.cookies.size > 0) headers["Cookie"] = this.cookieHeader();
    if (auth === "session" && this.sessionKey !== null && url.pathname.startsWith(PANEL_API_PREFIX)) headers[SESSION_KEY_HEADER] = this.sessionKey;
    if (auth === "api-key") {
      const apiKey = this.apiKey();
      if (!apiKey) throw new Error("no API key known for this instance yet");
      headers["X-API-Key"] = apiKey;
    }
    const response = await fetch(url, {
      method,
      headers,
      body: requestBody(request),
      redirect: "manual",
      signal: requestSignal(request.timeoutMs ?? 20_000),
    });
    const setCookies = response.headers.getSetCookie();
    const text = method === "HEAD" ? "" : await response.text();
    const json = parseJson(text);
    if (auth !== "none") {
      this.absorbCookies(setCookies);
      this.absorbSessionKey(json);
    }
    return {
      method,
      path,
      status: response.status,
      contentType: response.headers.get("content-type") ?? "",
      text: redactSessionKey(text),
      json: redactSessionKeyField(json),
      setCookies: setCookies.map(redactCookie),
    };
  }

  async bytes(path: string, options: ByteRequest = {}): Promise<ByteResult> {
    const url = this.resolve(path);
    const headers: Record<string, string> = {};
    if (options.range !== undefined) headers["Range"] = options.range;
    if (options.auth === "session") {
      if (url.origin !== new URL(this.baseUrl).origin) throw new Error(`refusing to send instance credentials to ${url.origin}`);
      if (this.cookies.size > 0) headers["Cookie"] = this.cookieHeader();
      if (this.sessionKey !== null && url.pathname.startsWith(PANEL_API_PREFIX)) headers[SESSION_KEY_HEADER] = this.sessionKey;
    }
    const response = await fetch(url, { headers, redirect: "manual", signal: requestSignal(60_000) });
    return {
      status: response.status,
      contentType: response.headers.get("content-type") ?? "",
      cacheControl: response.headers.get("cache-control") ?? "",
      contentRange: response.headers.get("content-range") ?? "",
      body: Buffer.from(await response.arrayBuffer()),
    };
  }

  private cookieHeader(): string {
    return [...this.cookies].map(([name, value]) => `${name}=${value}`).join("; ");
  }

  private absorbSessionKey(json: unknown): void {
    const sessionKey = jsonString(json, SESSION_KEY_FIELD);
    if (sessionKey) this.sessionKey = sessionKey;
  }

  private absorbCookies(setCookies: string[]): void {
    for (const header of setCookies) {
      const pair = header.split(";")[0] ?? "";
      const separator = pair.indexOf("=");
      if (separator <= 0) continue;
      const name = pair.slice(0, separator).trim();
      const value = pair.slice(separator + 1).trim();
      const expired = /max-age=0\b/i.test(header) || value === "";
      if (expired) this.cookies.delete(name);
      else this.cookies.set(name, value);
    }
  }
}

function requestBody(request: HttpRequest): string | Uint8Array<ArrayBuffer> | null {
  if (request.octets !== undefined) return new Uint8Array(request.octets);
  return request.json === undefined ? null : JSON.stringify(request.json);
}

function parseJson(text: string): unknown {
  if (text.trim() === "") return null;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

function redactCookie(header: string): string {
  return header.replace(/^([^=]+)=[^;]*/, "$1=<redacted>");
}

function redactSessionKey(text: string): string {
  return text.replace(new RegExp(`("${SESSION_KEY_FIELD}"\\s*:\\s*)"[^"]+"`, "g"), '$1"<redacted>"');
}

function redactSessionKeyField(json: unknown): unknown {
  const sessionKey = jsonString(json, SESSION_KEY_FIELD);
  if (typeof json !== "object" || json === null || sessionKey === null || sessionKey === "") return json;
  return { ...json, [SESSION_KEY_FIELD]: "<redacted>" };
}

export function jsonField(value: unknown, field: string): unknown {
  return typeof value === "object" && value !== null ? Reflect.get(value, field) : undefined;
}

export function jsonString(value: unknown, field: string): string | null {
  const raw = jsonField(value, field);
  return typeof raw === "string" ? raw : null;
}

export function jsonObject(value: unknown, field: string): object | null {
  const raw = jsonField(value, field);
  return typeof raw === "object" && raw !== null && !Array.isArray(raw) ? raw : null;
}

export function jsonArray(value: unknown, field: string): unknown[] {
  const raw = jsonField(value, field);
  return Array.isArray(raw) ? raw : [];
}

export function apiErrorCode(result: HttpResult): string | null {
  return jsonString(result.json, "code");
}

export function retryAfterSeconds(result: HttpResult): number | null {
  const raw = jsonField(result.json, "retryAfterSeconds");
  return typeof raw === "number" ? raw : null;
}
