import { z } from "zod";
import { containerLogs, removeSidecar, startSidecar } from "./docker";
import { CONTAINER_ASSETS_DIR } from "./paths";
import { randomSecret, sleep } from "./util";

const CONTAINER = "modl-testing-ai-stub";
const IMAGE = "node:24-alpine";
const ALIAS = "ai-stub";
const PORT = 8089;
const ASSETS_MOUNT = "/modl-testing";
const READY_LINE = "ai-stub listening";
const READY_TIMEOUT_MS = 30_000;

const STUB_REQUEST = z.object({
  stub: z.literal("ai"),
  path: z.string(),
  model: z.string().nullable(),
  authPresent: z.boolean(),
  authMatches: z.boolean(),
  responseFormat: z.string().nullable(),
  tokenField: z.string().nullable(),
  maxTokens: z.number().nullable(),
  samplingFields: z.array(z.string()),
});

export type StubRequest = z.infer<typeof STUB_REQUEST>;

function parseRequest(line: string): StubRequest[] {
  const start = line.indexOf('{"stub":"ai"');
  if (start < 0) return [];
  const value: unknown = JSON.parse(line.slice(start));
  return [STUB_REQUEST.parse(value)];
}

export class AiStub {
  readonly key: string;
  readonly origin = `http://${ALIAS}:${PORT}`;
  readonly baseUrl = `http://${ALIAS}:${PORT}/v1`;

  private constructor(key: string) {
    this.key = key;
  }

  static async start(project: string): Promise<AiStub> {
    const stub = new AiStub(`sk-modl-testing-${randomSecret()}`);
    await startSidecar({
      name: CONTAINER,
      image: IMAGE,
      network: `${project}_default`,
      alias: ALIAS,
      user: "node",
      volumes: [`${CONTAINER_ASSETS_DIR}:${ASSETS_MOUNT}:ro`],
      environment: { AI_STUB_KEY: stub.key },
      command: ["node", `${ASSETS_MOUNT}/ai-stub.mjs`],
    });
    await stub.awaitReady();
    return stub;
  }

  static async stop(): Promise<void> {
    await removeSidecar(CONTAINER);
  }

  async requests(): Promise<StubRequest[]> {
    return (await containerLogs(CONTAINER)).split("\n").flatMap(parseRequest);
  }

  private async awaitReady(): Promise<void> {
    const deadline = Date.now() + READY_TIMEOUT_MS;
    while (Date.now() < deadline) {
      if ((await containerLogs(CONTAINER)).includes(READY_LINE)) return;
      await sleep(500);
    }
    throw new Error(`${CONTAINER} did not start listening within ${READY_TIMEOUT_MS}ms`);
  }
}
