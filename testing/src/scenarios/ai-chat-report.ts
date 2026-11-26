import { AiEndpoints, AiErrors, AiFailures, AiOutputModes } from "../lib/ai-contract";
import { AiStub, type StubRequest } from "../lib/ai-stub";
import { readYamlValue } from "../lib/config-files";
import { Endpoints, REPORT_TICKET_ID } from "../lib/contract";
import { apiErrorCode, HttpStatus, jsonArray, jsonField, jsonObject, jsonString, retryAfterSeconds, type HttpMethod, type HttpResult } from "../lib/http";
import { connectionFile, patchConnection } from "../lib/topologies/server-files";
import { offlineUuid, sleep } from "../lib/util";
import { defineScenario, type ScenarioContext } from "./framework";
import { ownerSession } from "./support";

const PROVIDER = "custom";
const CASE_TIMEOUT_MS = 180_000;
const RESTART_TIMEOUT_MS = 420_000;
const ANALYSIS_TIMEOUT_MS = 30_000;
const CHAT_CAPTURE_MS = 1500;
const POLL_MS = 1000;
const DEFAULT_RETRY_AFTER_SECONDS = 5;
const CHAT_ABUSE = "6";
const SERVER_LOG = "plugins/modl/server/logs/server.log";
const SUMMARY_LINE = /\d+ warnings\/errors, see \S+/;
const ROOMY_LIMITS = { callsPerHour: 1000, callsPerDay: 10_000, reportsPerReporterPerHour: 5, concurrentCalls: 2, maxOutputTokens: 4096 };
const AI_CONFIG_KEYS = ["provider", "api-key", "model", "base-url"].map((key) => `embedded.ai.${key}`);

const FAILING_MODELS = [
  { model: "stub-malformed", failure: AiFailures.invalidResponse },
  { model: "stub-out-of-range", failure: AiFailures.invalidResponse },
  { model: "stub-truncated", failure: AiFailures.truncated },
  { model: "stub-refusal", failure: AiFailures.refused },
] as const;

interface FiledReport {
  ticketId: string;
  target: string;
}

interface OriginalSettings {
  activeProvider: string;
  limits: unknown;
  moderation: unknown;
}

async function send(t: ScenarioContext, method: HttpMethod, path: string, json?: unknown): Promise<HttpResult> {
  const result = await t.instance.api(path, { method, json, timeoutMs: 60_000 });
  if (result.status !== HttpStatus.tooManyRequests) return result;
  await sleep((retryAfterSeconds(result) ?? DEFAULT_RETRY_AFTER_SECONDS) * 1000);
  return t.instance.api(path, { method, json, timeoutMs: 60_000 });
}

async function expectOk(t: ScenarioContext, method: HttpMethod, path: string, json?: unknown): Promise<HttpResult> {
  const result = await send(t, method, path, json);
  t.require(result.status === HttpStatus.ok, `${method} ${path} returned ${result.status}: ${result.text.slice(0, 400)}`);
  return result;
}

async function expectRefusal(t: ScenarioContext, body: unknown, code: string): Promise<void> {
  const result = await send(t, "PUT", AiEndpoints.connection(PROVIDER), body);
  t.check(`the connection is refused with ${code}`, result.status === HttpStatus.badRequest && apiErrorCode(result) === code, result.json);
}

async function aiSettings(t: ScenarioContext): Promise<unknown> {
  return (await expectOk(t, "GET", AiEndpoints.settings)).json;
}

function customConnection(settings: unknown): unknown {
  return jsonArray(settings, "connections").find((connection) => jsonString(connection, "provider") === PROVIDER);
}

async function useModel(t: ScenarioContext, stub: AiStub, model: string): Promise<void> {
  await expectOk(t, "PUT", AiEndpoints.connection(PROVIDER), { baseUrl: stub.baseUrl, apiKey: stub.key, model, activate: true });
}

async function setLimits(t: ScenarioContext, limits: unknown): Promise<void> {
  const current = await aiSettings(t);
  await expectOk(t, "PUT", AiEndpoints.settings, { limitsUpdate: { expectedVersion: jsonField(current, "limitsVersion") ?? 0, limits } });
}

async function setModeration(t: ScenarioContext, values: object): Promise<void> {
  const current = (await expectOk(t, "GET", AiEndpoints.moderation)).json;
  await expectOk(t, "PATCH", AiEndpoints.moderation, { ...values, expectedVersion: jsonField(current, "version") ?? 0 });
}

async function enableAutomation(t: ScenarioContext): Promise<void> {
  await setModeration(t, {
    enableAIReview: true,
    enableAutomatedActions: true,
    aiPunishmentConfigs: {
      [CHAT_ABUSE]: { id: CHAT_ABUSE, name: "Chat Abuse", aiDescription: "Spamming, profanity or abusive language", enabled: true },
    },
  });
}

async function restoreSettings(t: ScenarioContext, original: OriginalSettings): Promise<void> {
  await send(t, "DELETE", AiEndpoints.connection(PROVIDER));
  await setLimits(t, original.limits);
  await expectOk(t, "PUT", AiEndpoints.settings, { activeProvider: original.activeProvider });
  await setModeration(t, {
    enableAIReview: jsonField(original.moderation, "enableAIReview") === true,
    enableAutomatedActions: jsonField(original.moderation, "enableAutomatedActions") === true,
    aiPunishmentConfigs: jsonField(original.moderation, "aiPunishmentConfigs") ?? {},
  });
}

async function fileChatReport(t: ScenarioContext): Promise<FiledReport> {
  const target = await t.bot(t.playerName("AiT"));
  const reporter = await t.bot(t.playerName("AiR"));
  target.chat("you are all useless trash players");
  await sleep(CHAT_CAPTURE_MS);
  const since = reporter.lastSeq;
  await reporter.runCommand(`/chatreport ${target.username}`);
  const message = await reporter.awaitMessage(REPORT_TICKET_ID, 20_000, since);
  const ticketId = REPORT_TICKET_ID.exec(message.text)?.[1];
  t.require(ticketId, `no ticket id in "${message.text}"`);
  return { ticketId, target: target.username };
}

async function ticketOf(t: ScenarioContext, report: FiledReport): Promise<unknown> {
  return (await expectOk(t, "GET", Endpoints.ticket(report.ticketId))).json;
}

async function awaitAnalysis(t: ScenarioContext, report: FiledReport): Promise<unknown> {
  const deadline = Date.now() + ANALYSIS_TIMEOUT_MS;
  while (Date.now() < deadline) {
    const ticket = await ticketOf(t, report);
    if (jsonString(jsonField(ticket, "aiAnalysis"), "analysis")) return ticket;
    await sleep(POLL_MS);
  }
  return t.fail(`ticket ${report.ticketId} received no AI analysis`);
}

async function awaitFailure(t: ScenarioContext, failure: string): Promise<unknown> {
  const deadline = Date.now() + ANALYSIS_TIMEOUT_MS;
  while (Date.now() < deadline) {
    const health = jsonField(await aiSettings(t), "health");
    if (jsonString(health, "lastFailure") === failure) return health;
    await sleep(POLL_MS);
  }
  return t.fail(`the AI health never reported ${failure}`);
}

async function punishmentCount(t: ScenarioContext, report: FiledReport): Promise<number> {
  const result = await t.instance.apiWithCredentials(Endpoints.minecraftPlayer(offlineUuid(report.target)), { auth: "api-key" });
  t.require(result.status === HttpStatus.ok, `reading ${report.target} returned ${result.status}`);
  return jsonArray(jsonField(result.json, "profile"), "punishments").length;
}

async function checkUntouched(t: ScenarioContext, report: FiledReport): Promise<void> {
  const analysis = jsonField(await ticketOf(t, report), "aiAnalysis");
  t.check("the ticket has no analysis", !jsonString(analysis, "analysis"), analysis);
  t.check("nobody was punished", (await punishmentCount(t, report)) === 0);
}

async function requestsAfter(stub: AiStub, count: number): Promise<StubRequest[]> {
  return (await stub.requests()).slice(count);
}

async function checkAddresses(t: ScenarioContext, stub: AiStub): Promise<void> {
  await t.step("blocked, public plain http and incomplete connections are refused", async () => {
    await expectRefusal(t, { baseUrl: "http://169.254.169.254/v1" }, AiErrors.addressBlocked);
    await expectRefusal(t, { baseUrl: "http://8.8.8.8/v1" }, AiErrors.httpPublic);
    await expectRefusal(t, { baseUrl: stub.baseUrl, activate: true }, AiErrors.notReady);
  });
}

async function checkCatalogAndProbe(t: ScenarioContext, stub: AiStub): Promise<void> {
  await t.step("models are listed with chat capability and without the key", async () => {
    const connection = { provider: PROVIDER, baseUrl: stub.baseUrl, apiKey: stub.key };
    const result = await expectOk(t, "POST", AiEndpoints.models, { connection, refresh: true });
    const models = jsonArray(result.json, "models");
    const chatCapable = (id: string) => jsonField(models.find((model) => jsonString(model, "id") === id), "chatCapable") === true;
    t.check("the chat model is chat capable", chatCapable("stub-violation"), models);
    t.check("the embedding model is not", models.length > 0 && !chatCapable("stub-text-embedding"), models);
    t.check("the model list never contains the key", !result.text.includes(stub.key));
    t.check("the settings never contain the key", !JSON.stringify(await aiSettings(t)).includes(stub.key));
  });

  await t.step("redirects are reported and never followed", async () => {
    const before = (await stub.requests()).length;
    const connection = { provider: PROVIDER, baseUrl: `${stub.origin}/redirect/v1`, apiKey: stub.key };
    const result = await expectOk(t, "POST", AiEndpoints.models, { connection, refresh: true });
    const sent = await requestsAfter(stub, before);
    t.check("the failure is a redirect", jsonString(result.json, "failure") === AiFailures.redirect, result.json);
    t.check("there was no second request", sent.length === 1, sent);
  });

  await t.step("the connection test passes with a schema and no sampling fields", async () => {
    const before = (await stub.requests()).length;
    const connection = { provider: PROVIDER, baseUrl: stub.baseUrl, apiKey: stub.key, model: "stub-violation" };
    const result = await expectOk(t, "POST", AiEndpoints.test, { connection });
    const sent = (await requestsAfter(stub, before))[0];
    t.check("the test succeeds", jsonField(result.json, "success") === true, result.json);
    t.check("the output follows a schema", jsonString(result.json, "outputMode") === AiOutputModes.schema, result.json);
    t.check("max_tokens is 4096", sent?.tokenField === "max_tokens" && sent.maxTokens === 4096, sent);
    t.check("no sampling field was sent", sent?.samplingFields.length === 0, sent);
    t.check("the key was sent as a bearer token", sent?.authMatches === true, sent);
  }, { timeoutMs: CASE_TIMEOUT_MS });
}

async function checkVerdicts(t: ScenarioContext, stub: AiStub): Promise<void> {
  await t.step("a confident violation is punished and the ticket closed", async () => {
    await useModel(t, stub, "stub-violation");
    await enableAutomation(t);
    const report = await fileChatReport(t);
    const ticket = await awaitAnalysis(t, report);
    t.evidence("ticket", ticket);
    t.check("the analysis was applied", jsonField(jsonField(ticket, "aiAnalysis"), "wasAppliedAutomatically") === true);
    t.check("the ticket is locked", jsonField(ticket, "locked") === true);
    t.check("the punishment exists", (await punishmentCount(t, report)) === 1);
    t.quitAllBots();
  }, { timeoutMs: CASE_TIMEOUT_MS });

  await t.step("changing the model keeps automation on and a low confidence verdict is only suggested", async () => {
    await useModel(t, stub, "stub-low");
    const moderation = (await expectOk(t, "GET", AiEndpoints.moderation)).json;
    t.check("automated actions stay on", jsonField(moderation, "enableAutomatedActions") === true, moderation);
    const report = await fileChatReport(t);
    const analysis = jsonField(await awaitAnalysis(t, report), "aiAnalysis");
    t.check("a suggestion is stored", jsonObject(analysis, "suggestedAction") !== null, analysis);
    t.check("it is not applied", jsonField(analysis, "wasAppliedAutomatically") !== true, analysis);
    t.check("nobody was punished", (await punishmentCount(t, report)) === 0);
    t.quitAllBots();
  }, { timeoutMs: CASE_TIMEOUT_MS });

  await t.step("a clean verdict stores an analysis without a suggestion", async () => {
    await useModel(t, stub, "stub-clean");
    const analysis = jsonField(await awaitAnalysis(t, await fileChatReport(t)), "aiAnalysis");
    t.check("there is no suggestion", jsonObject(analysis, "suggestedAction") === null, analysis);
    t.quitAllBots();
  }, { timeoutMs: CASE_TIMEOUT_MS });

  await t.step("a model without schema support is negotiated down to a JSON object", async () => {
    await useModel(t, stub, "stub-no-schema");
    const before = (await stub.requests()).length;
    await awaitAnalysis(t, await fileChatReport(t));
    const sent = await requestsAfter(stub, before);
    const formats = sent.map((request) => request.responseFormat).join(",");
    t.check("exactly two requests were sent", formats === "json_schema,json_object", sent);
    const connection = customConnection(await aiSettings(t));
    t.check("the mode is saved", jsonString(connection, "outputMode") === AiOutputModes.jsonObject, connection);
    t.quitAllBots();
  }, { timeoutMs: CASE_TIMEOUT_MS });
}

async function checkFailures(t: ScenarioContext, stub: AiStub): Promise<void> {
  for (const { model, failure } of FAILING_MODELS) {
    await t.step(`${model} stores nothing and reports ${failure}`, async () => {
      await useModel(t, stub, model);
      const report = await fileChatReport(t);
      await awaitFailure(t, failure);
      await checkUntouched(t, report);
      t.quitAllBots();
    }, { timeoutMs: CASE_TIMEOUT_MS, ignoreProblems: [SUMMARY_LINE] });
  }

  await t.step("a rate limited request is retried", async () => {
    await useModel(t, stub, "stub-flaky");
    const before = (await stub.requests()).length;
    await awaitAnalysis(t, await fileChatReport(t));
    const sent = await requestsAfter(stub, before);
    t.check("two requests were sent", sent.length === 2, sent);
    t.quitAllBots();
  }, { timeoutMs: CASE_TIMEOUT_MS, ignoreProblems: [SUMMARY_LINE] });

  await t.step("a rejected key pauses analysis without signing the owner out, and a new model resumes it", async () => {
    await useModel(t, stub, "stub-unauthorized");
    const before = (await stub.requests()).length;
    const report = await fileChatReport(t);
    const health = await awaitFailure(t, AiFailures.auth);
    const sent = await requestsAfter(stub, before);
    t.check("one request was sent", sent.length === 1, sent);
    t.check("analysis is paused", Boolean(jsonString(health, "pausedUntil")), health);
    await checkUntouched(t, report);
    t.check("the owner is still signed in", (await t.instance.profile()) !== null);
    await useModel(t, stub, "stub-clean");
    const resumed = jsonField(await aiSettings(t), "health");
    t.check("the pause is cleared", !jsonString(resumed, "pausedUntil"), resumed);
    t.quitAllBots();
  }, { timeoutMs: CASE_TIMEOUT_MS, ignoreProblems: [SUMMARY_LINE] });
}

async function checkKeyRetentionAndBudget(t: ScenarioContext, stub: AiStub): Promise<void> {
  await t.step("a new URL never receives the saved key", async () => {
    const baseUrl = `${stub.origin}/other/v1`;
    const saved = await expectOk(t, "PUT", AiEndpoints.connection(PROVIDER), { baseUrl });
    t.check("the key is gone", jsonField(customConnection(saved.json), "hasApiKey") !== true, saved.json);
    const before = (await stub.requests()).length;
    await expectOk(t, "POST", AiEndpoints.models, { connection: { provider: PROVIDER, baseUrl }, refresh: true });
    const sent = await requestsAfter(stub, before);
    t.check("no auth header was sent", sent.length === 1 && sent[0]?.authPresent === false, sent);
  });

  await t.step("a used up hourly budget sends nothing", async () => {
    await useModel(t, stub, "stub-clean");
    await setLimits(t, { ...ROOMY_LIMITS, callsPerHour: 1 });
    const before = (await stub.requests()).length;
    const report = await fileChatReport(t);
    await awaitFailure(t, AiFailures.hourlyBudget);
    const sent = await requestsAfter(stub, before);
    t.check("no request was sent", sent.length === 0, sent);
    await checkUntouched(t, report);
    t.quitAllBots();
  }, { timeoutMs: CASE_TIMEOUT_MS, ignoreProblems: [SUMMARY_LINE] });
}

async function checkConfigManaged(t: ScenarioContext, stub: AiStub): Promise<void> {
  const server = t.coreServer;
  const { dataDir } = t.env.server(server);
  const original = Object.fromEntries(AI_CONFIG_KEYS.map((key) => [key, readYamlValue(connectionFile(dataDir), key) ?? ""]));
  try {
    await t.step("embedded.ai locks the connection and never echoes the key", async () => {
      const since = await t.env.log(server).mark();
      patchConnection(dataDir, {
        "embedded.ai.provider": PROVIDER,
        "embedded.ai.api-key": stub.key,
        "embedded.ai.model": "stub-clean",
        "embedded.ai.base-url": stub.baseUrl,
      });
      await t.restart(server);
      await t.instance.ensureOwnerSession();
      const settings = await expectOk(t, "GET", AiEndpoints.settings);
      const managed = jsonField(settings.json, "managed");
      for (const field of ["provider", "apiKey", "model", "baseUrl"]) {
        t.check(`${field} is managed`, jsonField(managed, field) === true, managed);
      }
      const write = await send(t, "PUT", AiEndpoints.connection(PROVIDER), { model: "stub-low" });
      t.check("writes are refused", write.status === HttpStatus.conflict && apiErrorCode(write) === AiErrors.managedByConfig, write.json);
      t.check("the settings never contain the key", !settings.text.includes(stub.key));
      t.check("the console never contains the key", !(await t.env.log(server).text(since)).join("\n").includes(stub.key));
      const serverLog = await t.env.readServerFile(server, SERVER_LOG);
      t.require(serverLog !== null, `${SERVER_LOG} could not be read`);
      t.check("server.log never contains the key", !serverLog.includes(stub.key));
    }, { timeoutMs: RESTART_TIMEOUT_MS });
  } finally {
    await t.step("embedded.ai is restored", async () => {
      patchConnection(dataDir, original);
      await t.restart(server);
      await t.instance.ensureOwnerSession();
    }, { timeoutMs: RESTART_TIMEOUT_MS, allowConsoleErrors: true });
  }
}

export const aiChatReport = defineScenario({
  name: "ai-chat-report",
  description: "chat reports reach a stub AI provider through the panel's connection: verdicts, negotiation, failures, limits, SSRF rules and configuration-managed fields",
  topologies: ["standalone"],
  async run(t: ScenarioContext) {
    await t.step("the owner has a panel session", async () => ownerSession(t));
    const original = await t.step("the current AI settings are read", async (): Promise<OriginalSettings> => {
      const settings = await aiSettings(t);
      t.require(customConnection(settings) === undefined, "this scenario needs an instance without a custom AI connection");
      const moderation = (await expectOk(t, "GET", AiEndpoints.moderation)).json;
      await setLimits(t, ROOMY_LIMITS);
      return { activeProvider: jsonString(settings, "activeProvider") ?? "", limits: jsonField(settings, "limits"), moderation };
    });
    try {
      const stub = await t.step("the AI stub starts", async () => AiStub.start(t.env.state.project), { timeoutMs: CASE_TIMEOUT_MS });
      await checkAddresses(t, stub);
      await checkCatalogAndProbe(t, stub);
      await checkVerdicts(t, stub);
      await checkFailures(t, stub);
      await checkKeyRetentionAndBudget(t, stub);
      await checkConfigManaged(t, stub);
    } finally {
      try {
        await t.step("the AI settings are restored", async () => restoreSettings(t, original), {
          timeoutMs: CASE_TIMEOUT_MS,
          allowConsoleErrors: true,
        });
      } finally {
        await AiStub.stop();
      }
    }
  },
});
