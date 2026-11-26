import { randomBytes, timingSafeEqual } from "node:crypto";
import { rmSync } from "node:fs";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { z } from "zod";
import type { Logger } from "../log";
import { errorMessage, readJson, writeJson } from "../util";
import { BOT_DAEMON_TOKEN_HEADER } from "./daemon-client";

export type DaemonRoute = (body: unknown) => unknown;

export interface BotDaemonServer {
  file: string;
  routes: Record<string, DaemonRoute>;
  health(): Record<string, unknown>;
  idleTimeoutMs: number | null;
  onShutdown(): void;
  logger: Logger;
}

class RequestRejected extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

const daemonFileOwner = z.object({ pid: z.number().optional() });

const QUERY_STRING = /\?.*$/s;

async function readRawBody(request: IncomingMessage): Promise<string> {
  return new Promise((resolve, reject) => {
    let raw = "";
    request.on("data", (chunk: Buffer) => {
      raw += chunk.toString("utf8");
    });
    request.once("error", reject);
    request.once("end", () => {
      resolve(raw);
    });
  });
}

async function readBody(request: IncomingMessage): Promise<unknown> {
  const raw = await readRawBody(request);
  if (!raw) return {};
  const parsed: unknown = JSON.parse(raw);
  return parsed;
}

export function serveBotDaemon(daemon: BotDaemonServer): void {
  const token = randomBytes(24).toString("hex");
  let lastRequestAt = Date.now();

  const presentsToken = (request: IncomingMessage): boolean => {
    const presented = request.headers[BOT_DAEMON_TOKEN_HEADER];
    if (typeof presented !== "string") return false;
    const expected = Buffer.from(token, "utf8");
    const actual = Buffer.from(presented, "utf8");
    return actual.length === expected.length && timingSafeEqual(actual, expected);
  };

  const authorize = (request: IncomingMessage): void => {
    if (request.headers.origin !== undefined) throw new RequestRejected(403, "requests from browsers are not accepted");
    if (!presentsToken(request)) throw new RequestRejected(401, "missing or wrong bot daemon token");
  };

  const routes: Record<string, DaemonRoute> = {
    ...daemon.routes,
    "GET /health": () => ({ pid: process.pid, ...daemon.health() }),
    "POST /shutdown": () => {
      setTimeout(shutdown, 50);
      return { stopping: true };
    },
  };

  const dispatch = async (request: IncomingMessage): Promise<unknown> => {
    authorize(request);
    const key = `${request.method ?? ""} ${(request.url ?? "/").replace(QUERY_STRING, "")}`;
    const route = routes[key];
    if (!route) throw new RequestRejected(404, `no route ${key}`);
    return route(await readBody(request));
  };

  const handle = async (request: IncomingMessage, response: ServerResponse): Promise<void> => {
    let status = 200;
    let payload: unknown;
    try {
      payload = { ok: true, result: await dispatch(request) };
      lastRequestAt = Date.now();
    } catch (error) {
      status = error instanceof RequestRejected ? error.status : 400;
      payload = { ok: false, error: errorMessage(error) };
    }
    response.writeHead(status, { "content-type": "application/json" });
    response.end(JSON.stringify(payload));
  };

  const server = createServer((request, response) => void handle(request, response));

  function shutdown(): void {
    daemon.onShutdown();
    if (readJson(daemon.file, daemonFileOwner, {}).pid === process.pid) rmSync(daemon.file, { force: true });
    server.close();
    setTimeout(() => process.exit(0), 300);
  }

  const listeningPort = (): number => {
    const address = server.address();
    if (address === null || typeof address === "string") throw new Error("the bot daemon is not listening on a TCP port");
    return address.port;
  };

  server.listen(0, "127.0.0.1", () => {
    const port = listeningPort();
    writeJson(daemon.file, { pid: process.pid, port, token, startedAt: new Date().toISOString() });
    daemon.logger.info(`listening on 127.0.0.1:${port} (pid ${process.pid})`);
  });

  const idleTimeoutMs = daemon.idleTimeoutMs;
  if (idleTimeoutMs !== null) {
    setInterval(() => {
      if (Date.now() - lastRequestAt > idleTimeoutMs) {
        daemon.logger.info("idle timeout reached");
        shutdown();
      }
    }, 60_000).unref();
  }

  process.on("SIGTERM", shutdown);
  process.on("SIGINT", shutdown);
  process.on("uncaughtException", (error) => daemon.logger.error(`uncaught: ${error.stack ?? error.message}`));
  process.on("unhandledRejection", (error) => daemon.logger.error(`unhandled: ${String(error)}`));
}
