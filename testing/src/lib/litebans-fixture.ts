import { copyFileSync, existsSync, readdirSync } from "node:fs";
import { basename, join } from "node:path";
import { requireLiteBansFixtureJar } from "./product";
import { MODL_FOLDER } from "./topologies/server-files";
import { offlineUuid, removePath, writeText } from "./util";

const PADDING_PLAYERS = 70;
const PADDING_REASON_LENGTH = 900_000;
const HOUR_MS = 3_600_000;
const DAY_MS = 24 * HOUR_MS;
const OLD_HISTORY_DAYS = 500;
const ID_SPAN = 1000;
const UNREADABLE_PLAYER = "00000000-0000-4000-8000-00000000dead";

export interface LiteBansSeed {
  banned: { name: string; uuid: string; banId: string; warningId: string; kickId: string };
  expired: { name: string; uuid: string; banId: string };
  lifted: { name: string; uuid: string; muteId: string; reason: string };
  scoped: { name: string; uuid: string; banId: string };
  withoutHistory: { uuid: string; banId: string };
}

function quoted(value: string): string {
  return `'${value.replaceAll("'", "''")}'`;
}

function seedPlayer(name: string) {
  return { name, uuid: offlineUuid(name) };
}

export function createSeed(playerName: (prefix: string) => string): { seed: LiteBansSeed; sql: string } {
  const now = Date.now();
  const base = (now % 1_000_000) * ID_SPAN;
  const seed: LiteBansSeed = {
    banned: { ...seedPlayer(playerName("Ban")), banId: `litebans-ban-${base + 1}`, warningId: `litebans-warning-${base + 1}`, kickId: `litebans-kick-${base + 1}` },
    expired: { ...seedPlayer(playerName("Exp")), banId: `litebans-ban-${base + 2}` },
    lifted: { ...seedPlayer(playerName("Lift")), muteId: `litebans-mute-${base + 1}`, reason: "Grüße, 世界" },
    scoped: { ...seedPlayer(playerName("Scope")), banId: `litebans-ban-${base + 3}` },
    withoutHistory: { uuid: offlineUuid(playerName("NoHist")), banId: `litebans-ban-${base + 4}` },
  };
  const history: string[] = [];
  const addHistory = (uuid: string, name: string, seenAt: number) => {
    history.push(`(${base + history.length + 1}, ${quoted(uuid)}, ${quoted(name)}, '198.51.100.9', ${seenAt})`);
  };
  for (const player of [seed.banned, seed.expired, seed.lifted, seed.scoped]) addHistory(player.uuid, player.name, now - OLD_HISTORY_DAYS * DAY_MS);
  addHistory(UNREADABLE_PLAYER, "Unreadable", now - DAY_MS);

  const issued = now - 2 * DAY_MS;
  const punishmentColumns = "(id, uuid, reason, banned_by_name, time, until, active, ipban, server_scope, removed_by_name, removed_by_date)";
  const bans = [
    `(${base + 1}, ${quoted(seed.banned.uuid)}, 'harness import', 'Console', ${issued}, -1, 1, 0, '*', NULL, NULL)`,
    `(${base + 2}, ${quoted(seed.expired.uuid)}, 'harness expired', 'Console', ${issued}, ${issued + HOUR_MS}, 0, 0, '*', '#expired', NULL)`,
    `(${base + 3}, ${quoted(seed.scoped.uuid)}, 'harness scoped', 'Console', ${issued}, -1, 1, 1, 'survival', NULL, NULL)`,
    `(${base + 4}, ${quoted(seed.withoutHistory.uuid)}, 'harness no history', 'Console', ${issued}, -1, 1, 0, '*', NULL, NULL)`,
  ];
  const mutes = [
    `(${base + 1}, ${quoted(seed.lifted.uuid)}, ${quoted(seed.lifted.reason)}, 'Console', ${issued}, -1, 0, 0, '*', 'Admin', ${issued + HOUR_MS})`,
  ];
  const padding = "x".repeat(PADDING_REASON_LENGTH);
  for (let index = 0; index < PADDING_PLAYERS; index += 1) {
    const name = `Pad${base}${index}`;
    mutes.push(`(${base + 10 + index}, ${quoted(offlineUuid(name))}, '${padding}', 'Console', ${issued}, ${issued + HOUR_MS}, 0, 0, '*', NULL, NULL)`);
  }
  const warnings = [`(${base + 1}, ${quoted(seed.banned.uuid)}, 'harness warning', 'Console', ${issued}, -1, 1, 0, '*', NULL, NULL)`];
  const kicks = [`(${base + 1}, ${quoted(seed.banned.uuid)}, 'harness kick', 'Console', ${issued}, 0, 0, 0, '*')`];
  const statements = [
    ...history.map((row) => `INSERT INTO litebans_history (id, uuid, name, ip, date) VALUES ${row}`),
    ...bans.map((row) => `INSERT INTO litebans_bans ${punishmentColumns} VALUES ${row}`),
    ...mutes.map((row) => `INSERT INTO litebans_mutes ${punishmentColumns} VALUES ${row}`),
    ...warnings.map((row) => `INSERT INTO litebans_warnings ${punishmentColumns} VALUES ${row}`),
    ...kicks.map((row) => `INSERT INTO litebans_kicks (id, uuid, reason, banned_by_name, time, until, active, ipban, server_scope) VALUES ${row}`),
  ];
  return { seed, sql: `${statements.join("\n")}\n` };
}

export function installLiteBansFixture(serverDir: string, sql: string): void {
  const jar = requireLiteBansFixtureJar();
  removeLiteBansFixture(serverDir);
  writeText(join(serverDir, "plugins", "LiteBans", "seed.sql"), sql);
  copyFileSync(jar, join(serverDir, "plugins", basename(jar)));
}

export function removeLiteBansFixture(serverDir: string): void {
  removePath(join(serverDir, "plugins", "litebans-fixture.jar"));
  removePath(join(serverDir, "plugins", "LiteBans"));
}

export function importFilesLeft(serverDir: string): string[] {
  const directory = join(serverDir, MODL_FOLDER, "import");
  return existsSync(directory) ? readdirSync(directory) : [];
}
