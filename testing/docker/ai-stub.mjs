import { createServer } from "node:http";

const PORT = 8089;
const EXPECTED_KEY = process.env.AI_STUB_KEY ?? "";
const MAX_BODY_BYTES = 1024 * 1024;
const SAMPLING_FIELDS = ["temperature", "top_p", "top_k"];
const CHAT_MODELS = [
  "stub-violation",
  "stub-low",
  "stub-clean",
  "stub-malformed",
  "stub-out-of-range",
  "stub-truncated",
  "stub-refusal",
  "stub-no-schema",
  "stub-unauthorized",
  "stub-flaky",
];
const MODEL_IDS = [...CHAT_MODELS, "stub-text-embedding"];
const MODEL_LIST_ROUTES = new Set(["/v1/models", "/other/v1/models"]);
const COMPLETION_ROUTES = new Set(["/v1/chat/completions", "/other/v1/chat/completions"]);

let flakyCalls = 0;

function send(response, status, body, headers = {}) {
  response.writeHead(status, { "content-type": "application/json", ...headers });
  response.end(JSON.stringify(body));
}

function verdict(punishmentTypeId, severity, confidence) {
  return JSON.stringify({ analysis: "Stub verdict.", confidence, punishmentTypeId, severity });
}

function choice(content, finishReason = "stop", message = {}) {
  return { choices: [{ index: 0, finish_reason: finishReason, message: { role: "assistant", content, ...message } }] };
}

function firstTypeId(body) {
  const allowed = body?.response_format?.json_schema?.schema?.properties?.punishmentTypeId?.enum ?? [];
  return allowed.find((id) => id !== "none") ?? "none";
}

function complete(response, body) {
  const clean = choice(verdict("none", "none", 0.9));
  switch (body?.model) {
    case "stub-violation":
      send(response, 200, choice(verdict(firstTypeId(body), "severe", 0.95)));
      return;
    case "stub-low":
      send(response, 200, choice(verdict(firstTypeId(body), "severe", 0.5)));
      return;
    case "stub-out-of-range":
      send(response, 200, choice(verdict(firstTypeId(body), "severe", 85)));
      return;
    case "stub-malformed":
      send(response, 200, choice("The reported player broke the rules."));
      return;
    case "stub-truncated":
      send(response, 200, choice('{"analysis":"cut', "length"));
      return;
    case "stub-refusal":
      send(response, 200, choice(null, "stop", { refusal: "I cannot help with that." }));
      return;
    case "stub-no-schema":
      if (body?.response_format?.type === "json_schema") {
        send(response, 400, { error: { param: "response_format", message: "response_format json_schema is not supported" } });
        return;
      }
      send(response, 200, clean);
      return;
    case "stub-unauthorized":
      send(response, 401, { error: { message: "invalid key" } });
      return;
    case "stub-flaky":
      flakyCalls += 1;
      if (flakyCalls % 2 === 1) {
        send(response, 429, { error: { message: "slow down" } });
        return;
      }
      send(response, 200, clean);
      return;
    case "stub-clean":
      send(response, 200, clean);
      return;
    default:
      send(response, 404, { error: { code: "model_not_found", message: "no such model" } });
  }
}

async function readBody(request) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > MAX_BODY_BYTES) throw new Error("request too large");
    chunks.push(chunk);
  }
  return size === 0 ? null : JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

function record(request, path, body) {
  const authorization = request.headers.authorization;
  const tokenField = ["max_tokens", "max_completion_tokens"].find((field) => body?.[field] !== undefined) ?? null;
  console.log(
    JSON.stringify({
      stub: "ai",
      path,
      model: typeof body?.model === "string" ? body.model : null,
      authPresent: authorization !== undefined,
      authMatches: authorization === `Bearer ${EXPECTED_KEY}`,
      responseFormat: typeof body?.response_format?.type === "string" ? body.response_format.type : null,
      tokenField,
      maxTokens: tokenField === null ? null : body[tokenField],
      samplingFields: SAMPLING_FIELDS.filter((field) => body?.[field] !== undefined),
    }),
  );
}

async function handle(request, response) {
  const { pathname } = new URL(request.url ?? "/", "http://ai-stub");
  if (pathname === "/health") {
    send(response, 200, { ok: true });
    return;
  }
  const body = await readBody(request);
  record(request, pathname, body);
  if (pathname === "/redirect/v1/models") {
    send(response, 302, {}, { location: "/v1/models" });
  } else if (MODEL_LIST_ROUTES.has(pathname)) {
    send(response, 200, { data: MODEL_IDS.map((id) => ({ id, object: "model" })) });
  } else if (COMPLETION_ROUTES.has(pathname) && request.method === "POST") {
    complete(response, body);
  } else {
    send(response, 404, { error: { message: "not found" } });
  }
}

const server = createServer((request, response) => {
  handle(request, response).catch(() => {
    send(response, 400, { error: { message: "bad request" } });
  });
});

process.on("SIGTERM", () => {
  server.close();
  process.exit(0);
});

server.listen(PORT, "0.0.0.0", () => {
  console.log("ai-stub listening");
});
