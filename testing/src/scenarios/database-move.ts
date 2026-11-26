import { join } from "node:path";
import { DatabaseMoveEndpoints, DatabaseMoveEnums, DatabaseMoveMessages, UpdateMessages } from "../lib/contract";
import { HttpStatus, jsonField, jsonObject, jsonString, type HttpResult } from "../lib/http";
import { PostgresSidecar, type PostgresConnection } from "../lib/postgres-sidecar";
import { errorMessage, removePath } from "../lib/util";
import { defineScenario, type ScenarioContext } from "./framework";
import { expectJoinRefused, ownerSession } from "./support";
import { awaitConsole, dataDirectory, expectDataIntact, expectSessionKept, seedBan, UPDATE_TIMEOUT_MS } from "./update-support";

const MOVE_TIMEOUT_MS = 600_000;
const PULL_TIMEOUT_MS = 660_000;
const SIDECAR_TIMEOUT_MS = 180_000;
const STEP_OPTIONS = { timeoutMs: MOVE_TIMEOUT_MS } as const;
const CLEANUP_OPTIONS = { timeoutMs: 2 * MOVE_TIMEOUT_MS, allowConsoleErrors: true } as const;
const CHOICE_FILE = "database-connection.json";
const FRESH_REMEDY = "rerun up standalone with --fresh before the next scenario";
const MOVE_ENDED = new RegExp(`${DatabaseMoveMessages.adopted.source}|${DatabaseMoveMessages.stopped.source}`);
const COPY_ENDED = new RegExp(`${DatabaseMoveMessages.restartsOnto.source}|${DatabaseMoveMessages.stopped.source}`);

type Connection = Readonly<Record<string, string | number | boolean>>;

function postgresConnection(postgres: PostgresConnection): Connection {
  return { kind: DatabaseMoveEnums.postgresql, ...postgres, tls: false };
}

const BUILT_IN: Connection = { kind: DatabaseMoveEnums.sqlite };

async function expectOk(t: ScenarioContext, method: "GET" | "POST", path: string, json?: unknown): Promise<HttpResult> {
  const result = await t.instance.api(path, { method, json, timeoutMs: 120_000 });
  t.require(result.status === HttpStatus.ok, `${method} ${path} returned ${result.status}: ${result.text.slice(0, 400)}`);
  return result;
}

async function currentKind(t: ScenarioContext): Promise<string> {
  const settings = (await expectOk(t, "GET", DatabaseMoveEndpoints.settings)).json;
  const kind = jsonString(jsonObject(settings, "current"), "kind");
  t.require(kind !== null, `GET ${DatabaseMoveEndpoints.settings} names no current database: ${JSON.stringify(settings).slice(0, 400)}`);
  return kind;
}

async function moveTo(t: ScenarioContext, connection: Connection, target: string): Promise<void> {
  const host = t.coreServer;
  const since = await t.env.log(host).mark();
  const tested = (await expectOk(t, "POST", DatabaseMoveEndpoints.test, { connection })).json;
  t.evidence("test", tested);
  t.check(`Test accepts ${target}`, jsonField(tested, "ok") === true, tested);
  const started = (await expectOk(t, "POST", DatabaseMoveEndpoints.move, { connection })).json;
  t.check("the built-in panel restarts by itself", jsonString(started, "restartMode") === DatabaseMoveEnums.restartInPlace, started);
  const copied = await awaitConsole(t, host, COPY_ENDED, since);
  t.require(!DatabaseMoveMessages.stopped.test(copied), `the move stopped: ${copied}`);
  await awaitConsole(t, host, DatabaseMoveMessages.restartingPanel, since);
  await awaitConsole(t, host, UpdateMessages.restarted, since);
  await awaitConsole(t, host, DatabaseMoveMessages.adopted, since);
  const stopped = await t.env.log(host).find(DatabaseMoveMessages.stopped, since);
  t.check("the move did not stop", stopped.length === 0, stopped.map((line) => line.text));
}

async function reportedKind(t: ScenarioContext): Promise<string | null> {
  try {
    await t.instance.ensureOwnerSession();
    const result = await t.instance.api(DatabaseMoveEndpoints.settings, { timeoutMs: 30_000 });
    return result.status === HttpStatus.ok ? jsonString(jsonObject(result.json, "current"), "kind") : null;
  } catch (error) {
    t.note(`reading the current database failed: ${errorMessage(error)}`);
    return null;
  }
}

async function movedBackInPanel(t: ScenarioContext): Promise<boolean> {
  const host = t.coreServer;
  try {
    const since = await t.env.log(host).mark();
    await expectOk(t, "POST", DatabaseMoveEndpoints.move, { connection: BUILT_IN });
    const ended = await t.env.log(host).waitFor(MOVE_ENDED, { since, timeoutMs: UPDATE_TIMEOUT_MS });
    t.evidence("move back", ended.text);
    return DatabaseMoveMessages.adopted.test(ended.text);
  } catch (error) {
    t.note(`moving back to the built-in database failed: ${errorMessage(error)}`);
    return false;
  }
}

async function forgetPanelChoice(t: ScenarioContext): Promise<void> {
  const host = t.coreServer;
  t.note(`removing ${CHOICE_FILE} and restarting ${host} so it starts on the built-in database`);
  t.quitAllBots();
  await t.env.stopGracefully(host);
  removePath(join(dataDirectory(t), CHOICE_FILE));
  try {
    await t.start(host);
  } catch (error) {
    t.fail(`${host} did not start again on the built-in database (${errorMessage(error)}); ${FRESH_REMEDY}`);
  }
}

async function leaveOnBuiltIn(t: ScenarioContext): Promise<void> {
  const kind = await reportedKind(t);
  if (kind === DatabaseMoveEnums.sqlite) return;
  if (kind !== null && (await movedBackInPanel(t)) && (await reportedKind(t)) === DatabaseMoveEnums.sqlite) return;
  await forgetPanelChoice(t);
  t.require((await reportedKind(t)) === DatabaseMoveEnums.sqlite, `the panel did not return to the built-in database; ${FRESH_REMEDY}`);
}

export const databaseMove = defineScenario({
  name: "database-move",
  description: "Settings > System > Database moves the built-in SQLite database to PostgreSQL and back, restarting only the built-in panel",
  topologies: ["standalone"],
  async run(t: ScenarioContext) {
    try {
      await t.step("the PostgreSQL image is present", async () => PostgresSidecar.pull(), { timeoutMs: PULL_TIMEOUT_MS });
      const postgres = await t.step("a PostgreSQL server starts beside the panel", async () => PostgresSidecar.start(t.env.state.project), {
        timeoutMs: SIDECAR_TIMEOUT_MS,
      });
      await t.step("the owner is signed in", async () => {
        await ownerSession(t);
        t.check("the panel starts on the built-in database", (await currentKind(t)) === DatabaseMoveEnums.sqlite);
      });
      const banned = await t.step("a ban is written before the move", async () => seedBan(t));
      const online = await t.step("a player is online", async () => t.bot(t.playerName("Stay")));

      await t.step(
        "Move copies everything to PostgreSQL and restarts the built-in panel on it",
        async () => {
          await moveTo(t, postgresConnection(postgres.connection), "PostgreSQL");
          t.check("the panel now uses PostgreSQL", (await currentKind(t)) === DatabaseMoveEnums.postgresql);
          t.check("PostgreSQL holds modl's tables", (await postgres.modlTables()) > 0);
          t.check("the player stayed online", online.connected);
        },
        STEP_OPTIONS,
      );

      const bannedOnPostgres = await t.step(
        "the data and the session moved with it",
        async () => {
          await expectSessionKept(t);
          await expectDataIntact(t, banned, "the move");
          return seedBan(t);
        },
        STEP_OPTIONS,
      );

      await t.step(
        "Move returns to the built-in database with what was written on PostgreSQL",
        async () => {
          await moveTo(t, BUILT_IN, "the built-in database");
          t.check("the panel uses the built-in database again", (await currentKind(t)) === DatabaseMoveEnums.sqlite);
          await expectSessionKept(t);
          t.expectMatch("the ban written before the first move is still enforced", await expectJoinRefused(t, banned), /ban/i);
          await expectDataIntact(t, bannedOnPostgres, "the move back");
        },
        STEP_OPTIONS,
      );
    } finally {
      try {
        await t.step("the panel is left on the built-in database", async () => leaveOnBuiltIn(t), CLEANUP_OPTIONS);
      } finally {
        await PostgresSidecar.stop();
      }
    }
  },
});
